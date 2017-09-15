/**
 * Copyright (c) 2015, CodiLime Inc.
 *
 * Owner: Piotr Zarówny
 */
'use strict';

var config = require('./package.json');


exports.config = {
  specs: [config.files.tests.e2e],
  baseUrl: config.env.dev.host + ':' + config.env.dev.port
};
