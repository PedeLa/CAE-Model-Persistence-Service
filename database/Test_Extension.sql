CREATE TABLE IF NOT EXISTS commedit.TestModel (
  modelId INT NOT NULL AUTO_INCREMENT,
  CONSTRAINT testModelPK PRIMARY KEY (modelId)
);

CREATE TABLE IF NOT EXISTS commedit.TestCase (
  testCaseId INT NOT NULL,
  modelId INT NOT NULL,
  name VARCHAR(255) NOT NULL,
  CONSTRAINT testCasePK PRIMARY KEY (testCaseId, modelId),
  CONSTRAINT testCaseModelFK FOREIGN KEY (modelId) REFERENCES commedit.TestModel(modelId) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS commedit.TestRequest (
  testRequestId INT NOT NULL,
  modelId INT NOT NULL,
  testCaseId INT NOT NULL,
  type VARCHAR(255) NOT NULL,
  url VARCHAR(255) NOT NULL,
  authSelectedAgent INT,
  body TEXT,
  CONSTRAINT testRequestPK PRIMARY KEY (testRequestId, modelId),
  CONSTRAINT testRequestModelFK FOREIGN KEY (modelId) REFERENCES commedit.TestModel(modelId) ON DELETE CASCADE,
  CONSTRAINT testRequestTestCaseFK FOREIGN KEY (testCaseId) REFERENCES commedit.TestCase(testCaseId) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS commedit.RequestAssertion (
  requestAssertionId INT NOT NULL,
  modelId INT NOT NULL,
  testRequestId INT NOT NULL,
  assertionType INT NOT NULL,
  CONSTRAINT requestAssertionPK PRIMARY KEY (requestAssertionId, modelId),
  CONSTRAINT requestAssertionModelFK FOREIGN KEY (modelId) REFERENCES commedit.TestModel(modelId) ON DELETE CASCADE,
  CONSTRAINT requestAssertionTestRequestFK FOREIGN KEY (testRequestId) REFERENCES commedit.TestRequest(testRequestId) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS commedit.StatusCodeAssertion (
  modelId INT NOT NULL,
  requestAssertionId INT NOT NULL,
  comparisonOperator INT NOT NULL,
  statusCodeValue INT NOT NULL,
  CONSTRAINT statusCodeAssertionPK PRIMARY KEY (requestAssertionId, modelId),
  CONSTRAINT statusCodeAssertionModelFK FOREIGN KEY (modelId) REFERENCES commedit.TestModel(modelId) ON DELETE CASCADE,
  CONSTRAINT statusCodeAssertionRequestAssertionFK FOREIGN KEY (requestAssertionId) REFERENCES commedit.RequestAssertion(requestAssertionId) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS commedit.BodyAssertion (
  modelId INT NOT NULL,
  requestAssertionId INT NOT NULL,
  operatorId INT NOT NULL,
  CONSTRAINT bodyAssertionPK PRIMARY KEY (requestAssertionId, modelId),
  CONSTRAINT bodyAssertionModelFK FOREIGN KEY (modelId) REFERENCES commedit.TestModel(modelId) ON DELETE CASCADE,
  CONSTRAINT bodyAssertionRequestAssertionFK FOREIGN KEY (requestAssertionId) REFERENCES commedit.RequestAssertion(requestAssertionId) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS commedit.BodyAssertionOperator (
  operatorId INT NOT NULL,
  modelId INT NOT NULL,
  inputType INT,
  inputValue VARCHAR(255),
  followedBy INT,
  CONSTRAINT bodyAssertionOperatorPK PRIMARY KEY (operatorId, modelId),
  CONSTRAINT bodyAssertionOperatorModelFK FOREIGN KEY (modelId) REFERENCES commedit.TestModel(modelId) ON DELETE CASCADE
);