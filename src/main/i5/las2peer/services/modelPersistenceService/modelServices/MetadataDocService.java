package i5.las2peer.services.modelPersistenceService.modelServices;

import java.util.Date;
import java.util.ArrayList;
import java.util.UUID;
import java.util.AbstractMap.SimpleEntry;
import java.util.HashMap;
import java.util.Map;
import java.util.Iterator;
import java.io.IOException;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.ResultSet;

import i5.las2peer.logging.L2pLogger;
import i5.las2peer.services.modelPersistenceService.model.EntityAttribute;
import i5.las2peer.services.modelPersistenceService.model.Model;
import i5.las2peer.services.modelPersistenceService.model.node.Node;
import i5.las2peer.services.modelPersistenceService.model.edge.Edge;
import i5.las2peer.services.modelPersistenceService.model.metadata.MetadataDoc;
import i5.las2peer.services.modelPersistenceService.model.modelAttributes.ModelAttributes;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Strings;
import com.fasterxml.jackson.databind.node.ArrayNode;

public class MetadataDocService {
    private Connection _connection;
    private L2pLogger _logger;
    private String _logPrefix = "[MetadataDoc Service] - %s";

    /**
     * Constructor
     * @param connection database connection
     * @param logger logger
     */
    public MetadataDocService(Connection connection, L2pLogger logger) {
        _connection = connection;
        _logger = logger;
        _logger.info(String.format(_logPrefix, "Construct new element service"));
    }

    /**
     * Map sql result set to object
     * @param queryResult result set to convert 
     */
    private MetadataDoc mapResultSetToObject(ResultSet queryResult) throws SQLException {
        _logger.info(String.format(_logPrefix, "Mapping result set to MetadataDoc object"));
        try {
            String componentId = queryResult.getString("componentId");
            String docType = queryResult.getString("docType");
            String docString = queryResult.getString("docString");
            String docInput = queryResult.getString("docInput");
            String urlDeployed = queryResult.getString("urlDeployed");
            Date timeCreated = queryResult.getDate("timeCreated");
            String timeEdited = queryResult.getString("timeEditedUnix");
            String timeDeployed = queryResult.getString("timeDeployedUnix");
            int version = queryResult.getInt("version");
            MetadataDoc model = new MetadataDoc(componentId, docType, docString, docInput, urlDeployed, timeCreated, timeEdited, timeDeployed, version);
            return model;
        } catch (SQLException e) {
            _logger.printStackTrace(e);
        }

        return new MetadataDoc();
    }

    /**
     * Get list of all metadata doc
     * @return list of all metadata doc
     */
    public ArrayList<MetadataDoc> getAll() throws SQLException {
        ArrayList<MetadataDoc> result = new ArrayList<MetadataDoc>();
        try {
            String query = "SELECT *, UNIX_TIMESTAMP(timeEdited) as 'timeEditedUnix', UNIX_TIMESTAMP(timeDeployed) as 'timeDeployedUnix' FROM MetadataDoc";
            PreparedStatement sqlQuery;
            sqlQuery = _connection.prepareStatement(query);
            _logger.info(String.format(_logPrefix, "Executing GET ALL query " + query));
            ResultSet queryResult = sqlQuery.executeQuery();
            while(queryResult.next()) {
                result.add(mapResultSetToObject(queryResult));
            }
            sqlQuery.close();
        } catch (SQLException e) {
            _logger.printStackTrace(e);
        }
        
        return result;
    }

    /**
     * Get metadata doc connection by component id
     * @param queryId id of metadata doc
     * @return founded metadata doc
     */
    public MetadataDoc getByComponentId(String queryId) throws SQLException {
        try {
            PreparedStatement sqlQuery;
            sqlQuery = _connection.prepareStatement("SELECT *, UNIX_TIMESTAMP(timeEdited) as 'timeEditedUnix', UNIX_TIMESTAMP(timeDeployed) as 'timeDeployedUnix' FROM MetadataDoc WHERE componentId = ? ORDER BY timeEdited DESC LIMIT 1;");
            sqlQuery.setString(1, queryId);
            _logger.info(String.format(_logPrefix, "Executing GET BY ID query with componentId " + queryId));
            ResultSet queryResult = sqlQuery.executeQuery();
            if(queryResult.next()) {
                MetadataDoc model = mapResultSetToObject(queryResult);
                sqlQuery.close();
                return model;
            } else {
            	throw new SQLException("Could not find metadata doc!");
            }
            
        } catch (SQLException e) {
            _logger.printStackTrace(e);
        }
        
        return new MetadataDoc();
    }

        /**
     * Get metadata doc connection by component id
     * @param queryId id of metadata doc
     * @return founded metadata doc
     */
    public MetadataDoc getByComponentIdVersion(String queryId, int version) throws SQLException {
        try {
            PreparedStatement sqlQuery;
            sqlQuery = _connection.prepareStatement("SELECT *, UNIX_TIMESTAMP(timeEdited) as 'timeEditedUnix', UNIX_TIMESTAMP(timeDeployed) as 'timeDeployedUnix' FROM MetadataDoc WHERE componentId = ? AND version = ? ORDER BY timeEdited DESC LIMIT 1;");
            sqlQuery.setString(1, queryId);
            sqlQuery.setInt(2, version);
            _logger.info(String.format(_logPrefix, "Executing GET BY ID & VERSION query with componentId " + queryId + " and version " + version));
            ResultSet queryResult = sqlQuery.executeQuery();
            if(queryResult.next()) {
                MetadataDoc model = mapResultSetToObject(queryResult);
                sqlQuery.close();
                return model;
            } else {
            	throw new SQLException("Could not find metadata doc!");
            }
            
        } catch (SQLException e) {
            _logger.printStackTrace(e);
        }
        
        return new MetadataDoc();
    }

    /**
     * Get metadata doc string by component id
     * @param queryId id of metadata doc
     * @return founded metadata doc string
     */
    public String getMetadataDocStringByComponentId(String queryId) {
        try {
            return getByComponentId(queryId).getDocString();
        } catch (SQLException e) {
            return "";
        }
    }

    /**
     * Get user inputted metadata doc string by component id
     * @param queryId id of metadata doc
     * @return founded metadata doc string
     */
    public String getUserInputMetadataDocStringByComponentId(String queryId) {
        try {
            return getByComponentId(queryId).getDocInput();
        } catch (SQLException e) {
            return "";
        }
    }

    /****** CREATE UPDATE MODEL GENERATED METADATA DOC */
    public void createUpdateModelGeneratedMetadata(String componentId, String modelGenerateMetadata, String docType, int version) throws SQLException {
        try {
            PreparedStatement sqlQuery = _connection.prepareStatement(
                    " INSERT INTO MetadataDoc(componentId, docString, docType, version) VALUES (?,?,?,?) " + 
                    " ON DUPLICATE KEY UPDATE docString=?, docType=?");
            sqlQuery.setString(1, componentId);
            sqlQuery.setString(2, modelGenerateMetadata);
            sqlQuery.setString(3, docType);
            sqlQuery.setInt(4, version);
            sqlQuery.setString(5, modelGenerateMetadata);
            sqlQuery.setString(6, docType);
            _logger.info(String.format(_logPrefix, "Executing model generated metadata CREATE UPDATE query"));
            sqlQuery.executeUpdate();
            sqlQuery.close();
        } catch (SQLException e) {
            _logger.printStackTrace(e);
            throw e;
        }
    }

    /****** CREATE UPDATE MODEL GENERATED METADATA DOC */
    public void createUpdateUserGeneratedMetadata(String componentId, String inputJson, int version) throws SQLException {
        try {
            String docType = "json";
            PreparedStatement sqlQuery = _connection.prepareStatement(
                    " INSERT INTO MetadataDoc(componentId, docInput, docType, version) VALUES (?,?,?,?) " + 
                    " ON DUPLICATE KEY UPDATE docInput=?, docType=?");
            sqlQuery.setString(1, componentId);
            sqlQuery.setString(2, inputJson);
            sqlQuery.setString(3, docType);
            sqlQuery.setInt(4, version);
            sqlQuery.setString(5, inputJson);
            sqlQuery.setString(6, docType);
            _logger.info(String.format(_logPrefix, "Executing user generated metadata CREATE UPDATE query"));
            sqlQuery.executeUpdate();
            sqlQuery.close();
        } catch (SQLException e) {
            _logger.printStackTrace(e);
            throw e;
        }
    }

    public void updateDeploymentDetails(Model model, String urlDeployed) throws SQLException {
        try {
            // check for model type
            String modelType = null;
            ArrayList<EntityAttribute> modelAttributes = model.getAttributes().getAttributes();
            for (EntityAttribute modelAttribute: modelAttributes) {
                if (modelAttribute.getName().equals("type")) {
                    modelType = modelAttribute.getValue();
                }
            }

            if (modelType.equals("application")) {

                ArrayList<Node> appNodes = model.getNodes();
                for (Node appNode: appNodes) {
                    // check for node name
                    String componentId = null;
                    ArrayList<EntityAttribute> nodeAttributes = appNode.getAttributes();
                    for (EntityAttribute nodeAttribute: nodeAttributes) {
                        if (nodeAttribute.getName().equals("label")) {
                            componentId = nodeAttribute.getValue();
                        }
                    }

                    if (componentId != null) {
                        PreparedStatement sqlQuery = _connection.prepareStatement(
                            " UPDATE MetadataDoc SET urlDeployed=?, timeDeployed=NOW() " + 
                            " WHERE componentId=? ");
                        sqlQuery.setString(1, urlDeployed);
                        sqlQuery.setString(2, componentId);

                        _logger.info(String.format(_logPrefix, "Executing update deployment query"));
                        sqlQuery.executeUpdate();
                        sqlQuery.close();
                    }
                }
            }
            
        } catch (SQLException e) {
            _logger.printStackTrace(e);
            throw e;
        }
    }

    /****** GENERIC CREATE UPDATE METADATA DOC */

    /**
	 * Insert new metadata doc
	 * @param insertModel model to insert
	 */
    public void create(MetadataDoc insertModel) throws SQLException {
        try {
            PreparedStatement sqlQuery = _connection.prepareStatement(
				"INSERT INTO MetadataDoc(componentId, docString, docType) VALUES (?,?,?);");
            sqlQuery.setString(1, insertModel.getComponentId());
            sqlQuery.setString(2, insertModel.getDocString());
            sqlQuery.setString(3, insertModel.getDocType());
            _logger.info(String.format(_logPrefix, "Executing generic CREATE query"));
            sqlQuery.executeUpdate();
            sqlQuery.close();
        } catch (SQLException e) {
            _logger.printStackTrace(e);
        }
	}

    /**
	 * Update metadata doc in database
	 * @param updateModel model to update
	 */
    public void update(MetadataDoc updateModel) throws SQLException {
        try {
            PreparedStatement sqlQuery = _connection.prepareStatement(
				"UPDATE MetadataDoc SET docString=?, docType=? WHERE componentId=?;");
            sqlQuery.setString(3, updateModel.getComponentId());
            sqlQuery.setString(1, updateModel.getDocString());
            sqlQuery.setString(2, updateModel.getDocType());
            _logger.info(String.format(_logPrefix, "Executing UPDATE query for component id " + updateModel.getComponentId()));
            sqlQuery.executeUpdate();
            sqlQuery.close();
        } catch (SQLException e) {
            _logger.printStackTrace(e);
        }
	}

    /**
	 * Deletes doc from database.
	 * @param queryId id to delete
	 */
	public void delete(String queryId) throws SQLException {
        try {
            PreparedStatement sqlQuery = _connection.prepareStatement("DELETE FROM MetadataDoc WHERE id = ?;");
            sqlQuery.setString(1, queryId);
            _logger.info(String.format(_logPrefix, "Executing DELETE query with id " + queryId));
            _logger.info(String.format(_logPrefix, "Executing DELETE query with id " + queryId));
            sqlQuery.executeUpdate();
            sqlQuery.close();
        } catch (SQLException e) {
            _logger.printStackTrace(e);
        }
	}

    /**
     * Convert model object to swagger json object
     * @param model CAE model, assumed valid
     */
    public String modelToSwagger(Model model) {
        // check for model type
        String modelType = null;
        int componentVersion = 1;
        ArrayList<EntityAttribute> modelAttributes = model.getAttributes().getAttributes();
        for (EntityAttribute modelAttribute: modelAttributes) {
            if (modelAttribute.getName().equals("type")) {
                modelType = modelAttribute.getValue();
            }

            if (modelAttribute.getName().equals("version")) {
                componentVersion = Integer.parseInt(modelAttribute.getValue());
            }
        }
        
        if (modelType.equals("microservice")) {
            return microserviceToSwagger(model, componentVersion);
        } else {
            return "{}";
        }
    }

    private String microserviceToSwagger(Model model, int componentVersion) {
        ObjectMapper mapper = new ObjectMapper();

        // maps for model to http methods, payloads, responses, path
        // simple entry path and object method
        HashMap<String, SimpleEntry<String, SimpleEntry<String, ObjectNode>>> httpMethodNodes = new HashMap<String, SimpleEntry<String, SimpleEntry<String, ObjectNode>>>();
        HashMap<String, ObjectNode> httpPayloadNodes = new HashMap<String, ObjectNode>();
        // simple entry code and response node
        HashMap<String, SimpleEntry<String, ObjectNode>> httpResponseNodes = new HashMap<String, SimpleEntry<String, ObjectNode>>();

        // maps http methods to payload and reponse nodes
        HashMap<String, ArrayList<ObjectNode>> httpMethodParameterNodes = new HashMap<String, ArrayList<ObjectNode>>();
        HashMap<String, ArrayList<SimpleEntry<String, ObjectNode>>> httpMethodResponsesNodes = new HashMap<String, ArrayList<SimpleEntry<String, ObjectNode>>>();
        ObjectNode pathsObject = mapper.createObjectNode();

        // maps http methods to path
        HashMap<String, ArrayList<SimpleEntry<String, ObjectNode>>> pathToHttpMethod = new HashMap<String, ArrayList<SimpleEntry<String, ObjectNode>>>();

        // maps for node info, description and schemas for now
        HashMap<String, String> nodeInformations = new HashMap<String, String>();
        HashMap<String, String> nodeSchemas = new HashMap<String, String>();

        // method id to produces & consumes
        HashMap<String, ArrayList<String>> methodToProduces = new HashMap<String, ArrayList<String>>();
        HashMap<String, ArrayList<String>> methodToConsumes = new HashMap<String, ArrayList<String>>();
        
        // create root, restful resource node, only 1 per microservice model
        ObjectNode rootObject = mapper.createObjectNode();

        // get user input metadata doc if exists
        String modelName = model.getAttributes().getName();
        String userInputMetadataDoc = getUserInputMetadataDocStringByComponentId(modelName);
        
        String description = "No description";
        String version = "1.0";
        String termsOfService = "LICENSE.txt";
        JsonNode definitionsNode = null;
        
        if (!Strings.isNullOrEmpty(userInputMetadataDoc)) {
            try {
                JsonNode metadataTree = mapper.readTree(userInputMetadataDoc);
                // get info node
                if (metadataTree.hasNonNull("info")) {
                    JsonNode infoNode = metadataTree.get("info");
                    description = infoNode.get("description").asText();
                    description = (description == null || description.isEmpty()) ? "" : description;
                    version = infoNode.get("version").asText();
                    termsOfService = infoNode.get("termsOfService").asText();
                }

                // get definitions
                if (metadataTree.hasNonNull("definitions")) {
                    definitionsNode = metadataTree.get("definitions");
                }

                // get node info nodes
                if (metadataTree.hasNonNull("nodes")) {
                    JsonNode nodesNode = metadataTree.get("nodes");
                    Iterator<Map.Entry<String, JsonNode>> nodes = nodesNode.fields();
                    while (nodes.hasNext()) {
                        Map.Entry<String, JsonNode> entry = (Map.Entry<String, JsonNode>) nodes.next();
                        JsonNode entryValue = entry.getValue();
                        if (entryValue.hasNonNull("description"))
                            nodeInformations.put(entry.getKey(), entryValue.get("description").asText());
                        if (entryValue.hasNonNull("schema"))
                            nodeSchemas.put(entry.getKey(), entryValue.get("schema").asText());
                    }
                }
            } catch (IOException ex) {
                _logger.printStackTrace(ex);
            }
        }

        //get basic attributes for first level
        rootObject.put("swagger", "2.0");

        // info object
        ModelAttributes attributes = model.getAttributes();
        ObjectNode infoObject = mapper.createObjectNode();
        infoObject.put("title", attributes.getName());

        // generated from widget
        infoObject.put("description", description);
        infoObject.put("version", version);
        infoObject.put("termsOfService", termsOfService);

        // Fixed value
        ArrayNode schemes = mapper.createArrayNode();
        schemes.add("http");
        rootObject.put("schemes", schemes);

        try {
            // ==================== PROCESS NODES ======================
            ArrayList<Node> nodes = model.getNodes();
            for(Node node: nodes) {
                switch(node.getType()) {
                    // process base restful node
                    case "RESTful Resource":
                        // get attributes
                        ArrayList<EntityAttribute> restAttributes = node.getAttributes();
                        for(EntityAttribute attribute: restAttributes) {
                            switch(attribute.getName()) {
                                case "path":
                                    String urlPath = attribute.getValue();
                                    // get host
                                    URL urlObject = new URL(urlPath);
                                    rootObject.put("host", urlObject.getHost().replace("http://", ""));
                                    rootObject.put("basePath", urlObject.getPath());
                                    break;
                                case "developer":
                                    ObjectNode contactNode = mapper.createObjectNode();
                                    contactNode.put("name", attribute.getValue());
                                    infoObject.put("contact", contactNode);
                                    break;
                                default:
                                    break;
                            };
                        };
                        break;
                    case "HTTP Method":
                        httpMethodNodes.put(node.getId(), nodeToHttpMethod(node));
                        break;
                    case "HTTP Payload":
                        // parameters
                        httpPayloadNodes.put(node.getId(), nodeToHttpPayload(node, nodeInformations, nodeSchemas));
                        break;
                    case "HTTP Response":
                        // produces
                        httpResponseNodes.put(node.getId(), nodeToHttpResponse(node, nodeInformations, nodeSchemas));
                        break;
                    default:
                        break;
                };
            };
        } catch(Exception e) {
            _logger.printStackTrace(e);
            return rootObject.toString();
        }

        try {
            // ==================== PROCESS EDGES ======================
            ArrayList<Edge> edges = model.getEdges();
            for(Edge edge: edges) {
                String sourceId = edge.getSourceNode();
                String targetId = edge.getTargetNode();
                switch(edge.getType()) {
                    case "RESTful Resource to HTTP Method":
                        // do not process since we're going to put every http method into the root anyway
                        break;
                    case "HTTP Method to HTTP Payload":
                        //get the http method & payload object node
                        if (httpMethodNodes.get(sourceId) != null) {
                            // get payload object node
                            ObjectNode httpPayloadNode = httpPayloadNodes.get(targetId);
                            if (httpPayloadNode != null) {
                                // get parameter type for consume list
                                String nodeType = (httpPayloadNode.hasNonNull("type")) ? httpPayloadNode.get("type").asText() : "application/json";
                                if (methodToConsumes.get(sourceId) != null) {
                                    if (!methodToConsumes.get(sourceId).contains(nodeType))
                                        methodToConsumes.get(sourceId).add(nodeType);
                                } else {
                                    ArrayList<String> consumesList = new ArrayList<String>();
                                    consumesList.add(nodeType);
                                    methodToConsumes.put(sourceId, consumesList);
                                }

                                // add to parameters list
                                if (httpMethodParameterNodes.get(sourceId) != null) {
                                    httpMethodParameterNodes.get(sourceId).add(httpPayloadNode);
                                } else {
                                    ArrayList<ObjectNode> payloadList = new ArrayList<ObjectNode>();
                                    payloadList.add(httpPayloadNode);
                                    httpMethodParameterNodes.put(sourceId, payloadList);
                                }
                            }
                        }
                        break;
                    case "HTTP Method to HTTP Response":
                        //get the http method & payload object node
                        if (httpMethodNodes.get(sourceId) != null) {
                            // get payload object node
                            SimpleEntry<String, ObjectNode> httpResponseNode = httpResponseNodes.get(targetId);
                            if (httpResponseNode != null) {
                                // get parameter type for produces list
                                String nodeType = (httpResponseNode.getValue().hasNonNull("schema")) ? "application/json" : "";
                                if (!nodeType.equals("")) {
                                    if (methodToProduces.get(sourceId) != null) {
                                        if (!methodToProduces.get(sourceId).contains(nodeType))
                                            methodToProduces.get(sourceId).add(nodeType);
                                    } else {
                                        ArrayList<String> producesList = new ArrayList<String>();
                                        producesList.add(nodeType);
                                        methodToProduces.put(sourceId, producesList);
                                    }
                                }

                                // add to payload list
                                if (httpMethodResponsesNodes.get(sourceId) != null) {
                                    httpMethodResponsesNodes.get(sourceId).add(httpResponseNode);
                                } else {
                                    ArrayList<SimpleEntry<String, ObjectNode>> responseList = new ArrayList<SimpleEntry<String, ObjectNode>>();
                                    responseList.add(httpResponseNode);
                                    httpMethodResponsesNodes.put(sourceId, responseList);
                                }
                            }
                        }
                        break;
                }

            }

        } catch(Exception e) {
            _logger.printStackTrace(e);
            return rootObject.toString();
        }

        // add info node & defintions
        rootObject.put("info", infoObject);

        // process definitions
        if (definitionsNode != null) {
            Iterator<Map.Entry<String, JsonNode>> definitionIterator = definitionsNode.fields();
            ObjectNode definitionObjectNode = mapper.createObjectNode();
            ObjectNode definitionPropertiesNode = mapper.createObjectNode();
            
            while (definitionIterator.hasNext()) {
                ObjectNode iteratorObjectNode = mapper.createObjectNode();
                Map.Entry<String, JsonNode> entry = (Map.Entry<String, JsonNode>) definitionIterator.next();
                JsonNode entryValue = entry.getValue();
                String entryKey = entry.getKey();
                iteratorObjectNode.put("type", "object");
                iteratorObjectNode.put("properties", entryValue);
                definitionObjectNode.put(entryKey, iteratorObjectNode);
            }
            rootObject.put("definitions", definitionObjectNode);
        }

        try {
            // ==================== PROCESS JSON OBJECT HTTP NODES ======================
            for(Map.Entry<String, SimpleEntry<String, SimpleEntry<String, ObjectNode>>> entry: httpMethodNodes.entrySet()) {
                // get the object node
                String methodId = entry.getKey();
                SimpleEntry<String, SimpleEntry<String, ObjectNode>> methodPathToTypeNode = entry.getValue();
                String methodPath = methodPathToTypeNode.getKey();

                SimpleEntry<String, ObjectNode> methodTypeToNode = methodPathToTypeNode.getValue();
                String methodType = methodTypeToNode.getKey();
                ObjectNode methodObjectNode = methodTypeToNode.getValue();

                // get consumes & produces list
                ArrayList<String> producesList = methodToProduces.get(methodId);
                ArrayList<String> consumesList = methodToConsumes.get(methodId);

                if (producesList == null)
                    producesList = new ArrayList<String>();
                if (consumesList == null)
                    consumesList = new ArrayList<String>();

                ArrayNode produces = mapper.createArrayNode();
                for (String produceString: producesList) {
                    produces.add(produceString);
                }
                
                ArrayNode consumes = mapper.createArrayNode();
                for (String consumeString: consumesList) {
                    consumes.add(consumeString);
                }
                    
                methodObjectNode.put("consumes", consumes);
                methodObjectNode.put("produces", produces);

                // get all parameters
                ArrayNode parameters = mapper.createArrayNode();
                ArrayList<ObjectNode> parametersArray = httpMethodParameterNodes.get(methodId);

                if (parametersArray != null) {
                    for (ObjectNode parameter: parametersArray) {
                        parameters.add(parameter);
                    }
                    methodObjectNode.put("parameters", parameters);
                }

                // get all responses
                ObjectNode responses = mapper.createObjectNode();
                ArrayList<SimpleEntry<String, ObjectNode>> responsesArray = httpMethodResponsesNodes.get(methodId);
                
                if (responsesArray != null) {
                    for (SimpleEntry<String, ObjectNode> response: responsesArray) {
                        responses.put(response.getKey(), response.getValue());
                    }
                    methodObjectNode.put("responses", responses);
                }

                // add to path map list
                if (pathToHttpMethod.get(methodPath) != null) {
                    pathToHttpMethod.get(methodPath).add(methodTypeToNode);
                } else {
                    ArrayList<SimpleEntry<String, ObjectNode>> pathList = new ArrayList<SimpleEntry<String, ObjectNode>>();
                    pathList.add(methodTypeToNode);
                    pathToHttpMethod.put(methodPath, pathList);
                }            

            }
        } catch(Exception e) {
            _logger.printStackTrace(e);
            return rootObject.toString();
        }

        try {
            // ==================== PROCESS JSON OBJECT PATH NODES ======================
            for(Map.Entry<String, ArrayList<SimpleEntry<String, ObjectNode>>> entry: pathToHttpMethod.entrySet()) {
                String path = entry.getKey();
                ObjectNode pathNode = mapper.createObjectNode();
                ArrayList<SimpleEntry<String, ObjectNode>> methodsArray = entry.getValue();
                for (SimpleEntry<String, ObjectNode> methodTypeAndNode: methodsArray) {
                    String methodType = methodTypeAndNode.getKey();
                    ObjectNode methodNode = methodTypeAndNode.getValue();
                    pathNode.put(methodType, methodNode);
                }
                pathsObject.put(path, pathNode);
            }
        } catch(Exception e) {
            _logger.printStackTrace(e);
            return rootObject.toString();
        }

        // add path node to root
        rootObject.put("paths", pathsObject);

        // save result to database
        try {
            createUpdateModelGeneratedMetadata(modelName, rootObject.toString(), "json", componentVersion);
        } catch (SQLException e) {
            _logger.printStackTrace(e);
        }
        _logger.info(rootObject.toString());
        
        return rootObject.toString();
    }

    private SimpleEntry<String, SimpleEntry<String, ObjectNode>> nodeToHttpMethod(Node node) {
        ObjectMapper mapper = new ObjectMapper();

        String methodType = "";
        String path = "";
        String operationId = "";
        
        // get attributes
        ArrayList<EntityAttribute> nodeAttributes = node.getAttributes();
        for(EntityAttribute attribute: nodeAttributes) {
            switch (attribute.getName()) {
                case "methodType":
                    methodType = attribute.getValue().toLowerCase();
                    break;
                case "name":
                    operationId = attribute.getValue();
                    break;
                case "path":
                    path = attribute.getValue();
                    // check if begin with "/", if not, add
                    if (!path.startsWith("/")) {
                        path = "/" + path;
                    }
                    break;
                default:
                    break;
            }
        }

        ObjectNode methodObject = mapper.createObjectNode();
        methodObject.put("operationId", operationId);

        if(Strings.isNullOrEmpty(path)) {
            path = "/";
        }

        // create objectnode and store in path key
        SimpleEntry<String, ObjectNode> methodTypeObject = new SimpleEntry<String, ObjectNode>(methodType, methodObject);
        SimpleEntry<String, SimpleEntry<String, ObjectNode>> mapObject = new SimpleEntry<String, SimpleEntry<String, ObjectNode>>(path, methodTypeObject);
        return mapObject;
    }

    private ObjectNode nodeToHttpPayload(Node node, HashMap<String, String> nodeInformations, HashMap<String, String> nodeSchemas) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode nodeObject = mapper.createObjectNode();

        String name = "";
        String type = "";
        
        // get attributes
        ArrayList<EntityAttribute> nodeAttributes = node.getAttributes();
        for(EntityAttribute attribute: nodeAttributes) {
            switch (attribute.getName()) {
                case "name":
                    name = attribute.getValue();
                    break;
                case "payloadType":
                    type = attribute.getValue();
                    break;
                default:
                    break;
            }
        }

        nodeObject.put("name", name);
        nodeObject.put("required", true);
        type = TypeToOpenApiSpec(type);

        ObjectNode schemaObject = mapper.createObjectNode();
        if (type.equals("application/json")) {
            // search for the schema
            String nodeSchema = nodeSchemas.get(node.getId());
            if (nodeSchema != null && !nodeSchema.isEmpty()) {
                schemaObject.put("$ref", "#/definitions/" + nodeSchema);
                nodeObject.put("schema", schemaObject);
                nodeObject.put("in", "body");
            } else {
                // schema empty even if application json chosen
                nodeObject.put("type", "string");
                nodeObject.put("in", "query"); 
            }
        }
        else {
            nodeObject.put("type", type);
            nodeObject.put("in", "query"); 
        }

        // get description from node informations
        String description = nodeInformations.get(node.getId());
        description = (description == null || description.isEmpty()) ? "" : description;
        nodeObject.put("description", description);
        return nodeObject;
    }

    private String TypeToOpenApiSpec(String dataType) {
        switch (dataType) {
            case "JSON":
            case "application/json":
                return "application/json";
            case "TEXT":
            case "string":
                return "string";
            default:
                return "custom";
        }
    }

    private String StatusToCode(String status) {
/*HTTP_OK(200), HTTP_CREATED(201), HTTP_BAD_REQUEST(400), HTTP_UNAUTHORIZED(401), HTTP_NOT_FOUND(
        404), HTTP_CONFLICT(409), HTTP_INTERNAL_ERROR(500), HTTP_CUSTOM(-1);*/
        switch (status) {
            case "OK":
              return "200";
            case "CREATED":
              return "201";
            case "BAD_REQUEST":
              return "400";
            case "UNAUTHORIZED":
              return "401";
            case "NOT_FOUND":
              return "404";
            case "CONFLICT":
              return "409";
            case "INTERNAL_ERROR":
              return "500";
            case "CUSTOM":
              return "-1";
            default:
              return "";
          }
    }

    private SimpleEntry<String, ObjectNode> nodeToHttpResponse(Node node, HashMap<String, String> nodeInformations, HashMap<String, String> nodeSchemas) {
        ObjectMapper mapper = new ObjectMapper();

        String code = "";
        String name = "";
        String type = "";
        
        // get attributes
        ArrayList<EntityAttribute> nodeAttributes = node.getAttributes();
        for(EntityAttribute attribute: nodeAttributes) {
            switch (attribute.getName()) {
                case "name":
                    name = attribute.getValue();
                    break;
                case "returnStatusCode":
                    code = StatusToCode(attribute.getValue());
                    break;
                case "resultType":
                    type = attribute.getValue();
                    break;
                default:
                    break;
            }
        }

        ObjectNode responseObject = mapper.createObjectNode();
        ObjectNode schemaObject = mapper.createObjectNode();

        type = TypeToOpenApiSpec(type);
        if (type.equals("application/json")) {
            // search for the schema
            String nodeSchema = nodeSchemas.get(node.getId());
            if (nodeSchema != null && !nodeSchema.isEmpty()) {
                schemaObject.put("$ref", "#/definitions/" + nodeSchema);
                responseObject.put("schema", schemaObject);
            }
        }

        // get description from node informations
        String description = nodeInformations.get(node.getId());
        description = (description == null || description.isEmpty()) ? "" : description;
        responseObject.put("description", description);

        SimpleEntry<String, ObjectNode> mapObject = new SimpleEntry<String, ObjectNode>(code, responseObject);
        return mapObject;
    }
}