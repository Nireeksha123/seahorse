/**
 * Copyright (c) 2015, CodiLime Inc.
 */

package io.deepsense.workflowmanager.rest

import scala.concurrent._

import org.mockito.Matchers._
import org.mockito.Mockito._
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import spray.http.HttpHeaders.{`Content-Disposition`, RawHeader}
import spray.http._
import spray.json._
import spray.routing.Route

import io.deepsense.commons.auth.directives.AuthDirectives
import io.deepsense.commons.auth.usercontext.{TokenTranslator, UserContext}
import io.deepsense.commons.auth.{AuthorizatorProvider, UserContextAuthorizator}
import io.deepsense.commons.models.Id
import io.deepsense.commons.{StandardSpec, UnitTestSupport}
import io.deepsense.deeplang.DOperationCategories
import io.deepsense.deeplang.catalogs.doperable.DOperableCatalog
import io.deepsense.deeplang.catalogs.doperations.DOperationsCatalog
import io.deepsense.deeplang.doperations.FileToDataFrame
import io.deepsense.deeplang.inference.InferContext
import io.deepsense.graph._
import io.deepsense.models.json.graph.GraphJsonProtocol.GraphReader
import io.deepsense.models.json.workflow.{WorkflowJsonProtocol, WorkflowWithKnowledgeJsonProtocol, WorkflowWithVariablesJsonProtocol}
import io.deepsense.models.workflows._
import io.deepsense.workflowmanager.storage.WorkflowStorage
import io.deepsense.workflowmanager.{WorkflowManager, WorkflowManagerImpl, WorkflowManagerProvider}

class WorkflowsApiSpec
  extends StandardSpec
  with UnitTestSupport
  with ApiSpecSupport
  with WorkflowJsonProtocol
  with WorkflowWithKnowledgeJsonProtocol
  with WorkflowWithVariablesJsonProtocol {

  val catalog = DOperationsCatalog()
  catalog.registerDOperation[FileToDataFrame](
    DOperationCategories.IO,
    "Converts a file to a DataFrame"
  )

  val dOperableCatalog = new DOperableCatalog
  override val inferContext: InferContext = new InferContext(dOperableCatalog, true)
  override val graphReader: GraphReader = new GraphReader(catalog)

  def newWorkflowAndKnowledge: (Workflow, GraphKnowledge) = {
    val node1 = Node(Node.Id.randomId, FileToDataFrame())
    val node2 = Node(Node.Id.randomId, FileToDataFrame())
    val graph = Graph(Set(node1, node2), Set(Edge(node1, 0, node2, 0)))
    val metadata = WorkflowMetadata(WorkflowType.Batch, apiVersion = "0.1.1")
    val thirdPartyData = ThirdPartyData("{}")
    val knowledge = graph.inferKnowledge(inferContext)
    val workflow = Workflow(metadata, graph, thirdPartyData)
    (workflow, knowledge)
  }

  val (workflowA, knowledgeA) = newWorkflowAndKnowledge
  val workflowAId = Workflow.Id.randomId

  def cyclicWorkflow: Workflow = {
    val node1 = Node(Node.Id.randomId, FileToDataFrame())
    val node2 = Node(Node.Id.randomId, FileToDataFrame())
    val graph = Graph(Set(node1, node2), Set(Edge(node1, 0, node2, 0), Edge(node2, 0, node1, 0)))
    val metadata = WorkflowMetadata(WorkflowType.Batch, apiVersion = "0.1.2")
    val thirdPartyData = ThirdPartyData("{}")
    val workflow = Workflow(metadata, graph, thirdPartyData)
    workflow
  }

  val tenantAId: String = "A"
  val tenantBId: String = "B"

  /**
   * A valid Auth Token of a user of tenant A. This user has to have roles
   * for all actions in WorkflowManager
   */
  def validAuthTokenTenantA: String = tenantAId

  /**
   * A valid Auth Token of a user of tenant B. This user has to have no roles.
   */
  def validAuthTokenTenantB: String = tenantBId

  val apiPrefix: String = "v1/workflows"

  val roleGet = "workflows:get"
  val roleUpdate = "workflows:update"
  val roleDelete = "workflows:delete"
  val roleCreate = "workflows:create"

 override val authTokens: Map[String, Set[String]] = Map(
    tenantAId -> Set(roleGet, roleUpdate, roleDelete, roleCreate),
    tenantBId -> Set()
  )

  override def createRestComponent(tokenTranslator: TokenTranslator): Route = {
    val workflowManagerProvider = mock[WorkflowManagerProvider]
    when(workflowManagerProvider.forContext(any(classOf[Future[UserContext]])))
      .thenAnswer(new Answer[WorkflowManager]{
      override def answer(invocation: InvocationOnMock): WorkflowManager = {
        val futureContext = invocation.getArgumentAt(0, classOf[Future[UserContext]])

        val authorizator = new UserContextAuthorizator(futureContext)
        val authorizatorProvider: AuthorizatorProvider = mock[AuthorizatorProvider]
        when(authorizatorProvider.forContext(any(classOf[Future[UserContext]])))
          .thenReturn(authorizator)

        val storage = MockStorage()
        new WorkflowManagerImpl(
          authorizatorProvider, storage, inferContext, futureContext,
          roleGet, roleUpdate, roleDelete, roleCreate)
      }
    })

    new SecureWorkflowApi(tokenTranslator, workflowManagerProvider,
      apiPrefix, graphReader, inferContext).route
  }

  s"GET /workflows/:id" should {
    "return Unauthorized" when {
      "invalid auth token was send (when InvalidTokenException occurs)" in {
        Get(s"/$apiPrefix/${Workflow.Id.randomId}") ~>
          addHeader("X-Auth-Token", "its-invalid!") ~> testRoute ~> check {
          status should be(StatusCodes.Unauthorized)
        }
        ()
      }
      "the user does not have the requested role (on NoRoleException)" in {
        Get(s"/$apiPrefix/${Workflow.Id.randomId}") ~>
          addHeader("X-Auth-Token", validAuthTokenTenantB) ~> testRoute ~> check {
          status should be(StatusCodes.Unauthorized)
        }
        ()
      }
      "no auth token was send (on MissingHeaderRejection)" in {
        Get(s"/$apiPrefix/${Workflow.Id.randomId}") ~> testRoute ~> check {
          status should be(StatusCodes.Unauthorized)
        }
        ()
      }
    }
    "return Not found" when {
      "asked for non existing Workflow" in {
        Get(s"/$apiPrefix/${Workflow.Id.randomId}") ~>
          addHeader("X-Auth-Token", validAuthTokenTenantA) ~> testRoute ~> check {
          status should be(StatusCodes.NotFound)
        }
        ()
      }
    }
    "return an workflow" when {
      "auth token is correct, user has roles" in {
        Get(s"/$apiPrefix/$workflowAId") ~>
          addHeader("X-Auth-Token", validAuthTokenTenantA) ~> testRoute ~> check {
          status should be(StatusCodes.OK)

          // Checking if WorkflowWithKnowledge response is correct
          // This should be done better, but JsonReader is not available for WorkflowWithKnowledge
          val returnedWorkflow = responseAs[Workflow]
          returnedWorkflow should have(
            'metadata(workflowA.metadata),
            'graph(workflowA.graph),
            'additionalData(workflowA.additionalData)
          )
          val resultJs = response.entity.asString.parseJson.asJsObject
          resultJs.fields("knowledge") shouldBe knowledgeA.toJson.asJsObject.fields("resultsMap")
          resultJs.fields("id") shouldBe workflowAId.toJson
        }
        ()
      }
    }
  }

  s"DELETE /workflows/:id" should {
    "return Not found" when {
      "workflow does not exists" in {
        Delete(s"/$apiPrefix/${Workflow.Id.randomId}") ~>
          addHeader("X-Auth-Token", validAuthTokenTenantA) ~> testRoute ~> check {
          status should be(StatusCodes.NotFound)
        }
        ()
      }
    }
    "return Ok" when {
      "workflow existed and is deleted now" in {
        Delete(s"/$apiPrefix/$workflowAId") ~>
          addHeader("X-Auth-Token", validAuthTokenTenantA) ~> testRoute ~> check {
          status should be(StatusCodes.OK)
        }
        ()
      }
    }
    "return Unauthorized" when {
      "invalid auth token was send (when InvalidTokenException occurs)" in {
        Delete(s"/$apiPrefix/${Workflow.Id.randomId}") ~>
          addHeader("X-Auth-Token", "its-invalid!") ~> testRoute ~> check {
          status should be(StatusCodes.Unauthorized)
        }
        ()
      }
      "the user does not have the requested role (on NoRoleException)" in {
        Delete(s"/$apiPrefix/${Workflow.Id.randomId}") ~>
          addHeader("X-Auth-Token", validAuthTokenTenantB) ~> testRoute ~> check {
          status should be(StatusCodes.Unauthorized)
        }
        ()
      }
      "no auth token was send (on MissingHeaderRejection)" in {
        Delete(s"/$apiPrefix/${Workflow.Id.randomId}") ~> testRoute ~> check {
          status should be(StatusCodes.Unauthorized)
        }
      }
    }
  }

  s"GET /workflows/:id/download" should {
    "return Unauthorized" when {
      "invalid auth token was send (when InvalidTokenException occurs)" in {
        Get(s"/$apiPrefix/${Workflow.Id.randomId}/download?format=json") ~>
          addHeader("X-Auth-Token", "its-invalid!") ~> testRoute ~> check {
          status should be(StatusCodes.Unauthorized)
        }
        ()
      }
      "the user does not have the requested role (on NoRoleException)" in {
        Get(s"/$apiPrefix/${Workflow.Id.randomId}/download?format=json") ~>
          addHeader("X-Auth-Token", validAuthTokenTenantB) ~> testRoute ~> check {
          status should be(StatusCodes.Unauthorized)
        }
        ()
      }
      "no auth token was send (on MissingHeaderRejection)" in {
        Get(s"/$apiPrefix/${Workflow.Id.randomId}/download?format=json") ~> testRoute ~> check {
          status should be(StatusCodes.Unauthorized)
        }
        ()
      }
    }
    "return Not found" when {
      "asked for non existing Workflow" in {
        Get(s"/$apiPrefix/${Workflow.Id.randomId}/download?format=json") ~>
          addHeader("X-Auth-Token", validAuthTokenTenantA) ~> testRoute ~> check {
          status should be(StatusCodes.NotFound)
        }
        ()
      }
    }
    "return an workflow" when {
      "auth token is correct, user has roles" in {
        Get(s"/$apiPrefix/$workflowAId/download?format=json") ~>
          addHeader("X-Auth-Token", validAuthTokenTenantA) ~> testRoute ~> check {
          status should be(StatusCodes.OK)
          header("Content-Disposition") shouldBe Some(
            `Content-Disposition`("attachment", Map("filename" -> "workflow.json")))

          responseAs[WorkflowWithVariables] shouldBe WorkflowWithVariables(
            workflowAId,
            workflowA.metadata,
            workflowA.graph,
            workflowA.additionalData,
            Variables()
          )
        }
        ()
      }
    }
  }

  "POST /workflows" should {
    "process authorization before reading POST content" in {
      val invalidContent = JsObject()
      Post(s"/$apiPrefix", invalidContent) ~> testRoute ~> check {
        status should be(StatusCodes.Unauthorized)
      }
    }
    "return created" when {
      "inputWorkflow was send" in {
        val (createdWorkflow, knowledge) = newWorkflowAndKnowledge
        Post(s"/$apiPrefix", createdWorkflow) ~>
          addHeader("X-Auth-Token", validAuthTokenTenantA) ~> testRoute ~> check {
          status should be (StatusCodes.Created)

          // Checking if WorkflowWithKnowledge response is correct
          // This should be done better, but JsonReader is not available for WorkflowWithKnowledge
          val savedWorkflow = responseAs[Workflow]
          savedWorkflow should have (
            'metadata (createdWorkflow.metadata),
            'graph (createdWorkflow.graph),
            'additionalData (createdWorkflow.additionalData)
          )
          val resultJs = response.entity.asString.parseJson.asJsObject
          resultJs.fields("knowledge") shouldBe knowledge.toJson.asJsObject.fields("resultsMap")
          resultJs.fields should contain key "id"
        }
        ()
      }
    }
    "return BadRequest" when {
      "inputWorkflow contains cyclic graph" in {
        Post(s"/$apiPrefix", cyclicWorkflow) ~>
          addHeader("X-Auth-Token", validAuthTokenTenantA) ~> testRoute ~> check {
          status should be (StatusCodes.BadRequest)
        }
        ()
      }
    }
    "return Unauthorized" when {
      "invalid auth token was send (when InvalidTokenException occurs)" in {
        Post(s"/$apiPrefix", workflowA) ~>
          addHeader("X-Auth-Token", "its-invalid!") ~> testRoute ~> check {
          status should be(StatusCodes.Unauthorized)
        }
        ()
      }
      "the user does not have the requested role (on NoRoleExeption)" in {
        Post(s"/$apiPrefix", workflowA) ~>
          addHeader("X-Auth-Token", validAuthTokenTenantB) ~> testRoute ~> check {
          status should be(StatusCodes.Unauthorized)
        }
        ()
      }
      "no auth token was send (on MissingHeaderRejection)" in {
        Post(s"/$apiPrefix", workflowA) ~> testRoute ~> check {
          status should be(StatusCodes.Unauthorized)
        }
        ()
      }
    }
  }

  "POST /workflows/upload" should {
    "return created" when {
      "workflow file is sent" in {
        val (createdWorkflow, knowledge) = newWorkflowAndKnowledge

        val workflowJsonString =
          jsonFormat(Workflow.apply, "metadata", "workflow", "thirdPartyData")
            .write(createdWorkflow).toString()

        val multipartData = MultipartFormData(Map(
          "workflowFile" -> BodyPart(HttpEntity(
            ContentType(MediaTypes.`application/json`),
            workflowJsonString)
          )))

        Post(s"/$apiPrefix/upload", multipartData) ~>
          addHeaders(
            RawHeader("X-Auth-Token", validAuthTokenTenantA)) ~> testRoute ~> check {
          status should be (StatusCodes.Created)

          // Checking if WorkflowWithKnowledge response is correct
          // This should be done better, but JsonReader is not available for WorkflowWithKnowledge
          val savedWorkflow = responseAs[Workflow]
          savedWorkflow should have (
            'metadata (createdWorkflow.metadata),
            'graph (createdWorkflow.graph),
            'additionalData (createdWorkflow.additionalData))

          val resultJs = response.entity.asString.parseJson.asJsObject
          resultJs.fields("knowledge") shouldBe knowledge.toJson.asJsObject.fields("resultsMap")
          resultJs.fields should contain key "id"
        }
        ()
      }
    }
  }

  s"PUT /workflows/:id" should {
    val (workflow, knowledge) = newWorkflowAndKnowledge
    val updatedWorkflow = workflow.copy(metadata = workflow.metadata.copy(apiVersion = "x.y.z"))

    "process authorization before reading PUT content" in {
      val invalidContent = JsObject()
      Put(s"/$apiPrefix/" + Workflow.Id.randomId, invalidContent) ~> testRoute ~> check {
        status should be(StatusCodes.Unauthorized)
      }
    }
    "update the workflow and return Ok" when {
      "user updates his workflow" in {
        Put(s"/$apiPrefix/$workflowAId", updatedWorkflow) ~>
          addHeader("X-Auth-Token", validAuthTokenTenantA) ~> testRoute ~> check {
          status should be(StatusCodes.OK)

          // Checking if WorkflowWithKnowledge response is correct
          // This should be done better, but JsonReader is not available for WorkflowWithKnowledge
          val savedWorkflow = responseAs[Workflow]
          savedWorkflow should have(
            'graph (updatedWorkflow.graph),
            'metadata (updatedWorkflow.metadata),
            'additionalData (updatedWorkflow.additionalData)
          )
          val resultJs = response.entity.asString.parseJson.asJsObject
          resultJs.fields("knowledge") shouldBe knowledge.toJson.asJsObject.fields("resultsMap")
          resultJs.fields("id") shouldBe workflowAId.toJson
        }
        ()
      }
    }
    "return NotFound" when {
      "the workflow does not exist" in {
        val nonExistingId = Workflow.Id.randomId
        Put(s"/$apiPrefix/$nonExistingId", updatedWorkflow) ~>
          addHeader("X-Auth-Token", validAuthTokenTenantA) ~> testRoute ~> check {
          status should be(StatusCodes.NotFound)
        }
        ()
      }
    }
    "return Unauthorized" when {
      "invalid auth token was send (when InvalidTokenException occurs)" in {
        Put(s"/$apiPrefix/" + workflowAId, updatedWorkflow) ~>
          addHeader("X-Auth-Token", "its-invalid!") ~> testRoute ~> check {
          status should be(StatusCodes.Unauthorized)
        }
        ()
      }
      "the user does not have the requested role (on NoRoleExeption)" in {
        Put(s"/$apiPrefix/" + workflowAId, updatedWorkflow) ~>
          addHeader("X-Auth-Token", validAuthTokenTenantB) ~> testRoute ~> check {
          status should be(StatusCodes.Unauthorized)
        }
        ()
      }
      "no auth token was send (on MissingHeaderRejection)" in {
        Put(s"/$apiPrefix/" + workflowAId, updatedWorkflow) ~> testRoute ~> check {
          status should be(StatusCodes.Unauthorized)
        }
      }
    }
  }

  case class MockStorage() extends WorkflowStorage {
    private var storedWorkflows = Map(workflowAId -> workflowA)

    override def get(id: Id): Future[Option[Workflow]] = Future.successful(storedWorkflows.get(id))

    override def delete(id: Id): Future[Unit] = Future.successful(storedWorkflows -= id)

    override def save(id: Id, workflow: Workflow): Future[Unit] =
      Future.successful(storedWorkflows += (id -> workflow))
  }
}