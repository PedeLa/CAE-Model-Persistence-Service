package i5.las2peer.services.modelPersistenceService;

import java.io.Serializable;
import java.net.HttpURLConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;

import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.json.simple.parser.ParseException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import i5.cae.semanticCheck.SemanticCheckResponse;
import i5.cae.simpleModel.SimpleEntityAttribute;
import i5.cae.simpleModel.SimpleModel;
import i5.cae.simpleModel.node.SimpleNode;
import i5.las2peer.api.Context;
import i5.las2peer.api.ServiceException;
import i5.las2peer.api.execution.InternalServiceException;
import i5.las2peer.api.execution.ServiceAccessDeniedException;
import i5.las2peer.api.execution.ServiceInvocationFailedException;
import i5.las2peer.api.execution.ServiceMethodNotFoundException;
import i5.las2peer.api.execution.ServiceNotAuthorizedException;
import i5.las2peer.api.execution.ServiceNotAvailableException;
import i5.las2peer.api.execution.ServiceNotFoundException;
import i5.las2peer.api.logging.MonitoringEvent;
import i5.las2peer.logging.L2pLogger;
import i5.las2peer.services.modelPersistenceService.database.DatabaseManager;
import i5.las2peer.services.modelPersistenceService.exception.CGSInvocationException;
import i5.las2peer.services.modelPersistenceService.exception.ModelNotFoundException;
import i5.las2peer.services.modelPersistenceService.exception.VersionedModelNotFoundException;
import i5.las2peer.services.modelPersistenceService.model.EntityAttribute;
import i5.las2peer.services.modelPersistenceService.model.Model;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import io.swagger.jaxrs.Reader;
import io.swagger.models.Swagger;
import io.swagger.util.Json;
import i5.las2peer.services.modelPersistenceService.model.metadata.MetadataDoc;

import i5.las2peer.services.modelPersistenceService.modelServices.*;
import i5.las2peer.services.modelPersistenceService.versionedModel.Commit;
import i5.las2peer.services.modelPersistenceService.versionedModel.VersionedModel;

@Path("/")
public class RESTResources {

	private static final String PROJECT_MANAGEMENT_SERVICE = "i5.las2peer.services.projectManagementService.ProjectManagementService@0.1.0";
	
	private final ModelPersistenceService service = (ModelPersistenceService) Context.getCurrent().getService();
	private L2pLogger logger;
	private String semanticCheckService;
	private String codeGenerationService;
	private String deploymentUrl;
	private DatabaseManager dbm;
	private MetadataDocService metadataDocService;

	public RESTResources() throws ServiceException {
		this.logger = (L2pLogger) service.getLogger();
		this.semanticCheckService = service.getSemanticCheckService();
		this.codeGenerationService = service.getCodeGenerationService();
		this.deploymentUrl = service.getDeploymentUrl();
		this.dbm = service.getDbm();
		this.metadataDocService = service.getMetadataService();
	}

	/**
	 * 
	 * Entry point for all new models. Stores it to the database.
	 * 
	 * @param inputModel
	 *            the model as a JSON string
	 * 
	 * @return HttpResponse containing the status code of the request and a
	 *         small return message
	 * 
	 */
	@POST
	@Path("/models/")
	@Consumes(MediaType.APPLICATION_JSON)
	@ApiOperation(value = "Entry point for storing a model to the database.", notes = "Entry point for storing a model to the database.")
	@ApiResponses(value = { @ApiResponse(code = HttpURLConnection.HTTP_CREATED, message = "OK, model stored"),
			@ApiResponse(code = HttpURLConnection.HTTP_BAD_REQUEST, message = "Input model was not valid"),
			@ApiResponse(code = HttpURLConnection.HTTP_CONFLICT, message = "Tried to save a model that already had a name and thus was not new"),
			@ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error") })
	public Response postModel(String inputModel) {
		Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE, "postModel: trying to store new model");
		Model model;
		try {
			// create the model
			model = new Model(inputModel);
		} catch (ParseException e) {
			Context.get().monitorEvent(MonitoringEvent.SERVICE_ERROR, "postModel: exception parsing JSON input: " + e);
			throw new BadRequestException(e.getMessage());
			// return Response.serverError().build();
		} catch (Exception e) {
			Context.get().monitorEvent(MonitoringEvent.SERVICE_ERROR, "postModel: something went seriously wrong: " + e);
			logger.printStackTrace(e);
			return Response.serverError().entity("Internal server error!").build();
		}

		// do the semantic check
		if (!semanticCheckService.isEmpty()) {
			this.checkModel(model);
		}
		
	    // PROBLEM: The codegen service and metadatadocservice already require the model to have a "type" attribute
		// for first testing we just always set it to fronend-component
		// TODO: this should not always just set the type to frontend-component
		model.getAttributes().add(new EntityAttribute(new SimpleEntityAttribute("syncmetaid", "type", "frontend-component")));

		// call code generation service
		if (!codeGenerationService.isEmpty()) {
			try {
				// get user input metadata doc if available
				String metadataDocString = model.getMetadataDoc();
				
				if (metadataDocString == null)
					metadataDocString = "";

				Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE, "postModel: invoking code generation service..");
				// TODO: reactivate usage of code generation service
				// TODO: EDIT: is reactivated now, check if everything works, then this TODO can be removed
				model = callCodeGenerationService("createFromModel", model, metadataDocString, null);
			} catch (CGSInvocationException e) {
				return Response.serverError().entity("Model not valid: " + e.getMessage()).build();
			}
		}
		
		// generate metadata swagger doc after model valid in code generation
		metadataDocService.modelToSwagger(model);

		// save the model to the database
		Connection connection = null;
		try {
			connection = dbm.getConnection();
			model.persist(connection);
			int modelId = model.getId();
			Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE, "postModel: model with id " + modelId + " stored!");
			return Response.status(201).entity("Model stored!").build();
		} catch (SQLException e) {
			Context.get().monitorEvent(MonitoringEvent.SERVICE_ERROR, "postModel: exception persisting model: " + e);
			logger.printStackTrace(e);
			return Response.serverError().entity("Could not persist, database rejected model!").build();
		} catch (Exception e) {
			Context.get().monitorEvent(MonitoringEvent.SERVICE_ERROR, "postModel: something went seriously wrong: " + e);
			logger.printStackTrace(e);
			return Response.serverError().entity("Internal server error...").build();
		}
		// always close connections
		finally {
			try {
				connection.close();
			} catch (SQLException e) {
				logger.printStackTrace(e);
			}
		}
	}

	/**
	 * 
	 * Searches for a model in the database by name.
	 * 
	 * @param modelId
	 *            the id of the model
	 * 
	 * @return HttpResponse containing the status code of the request and (if
	 *         successful) the model as a JSON string
	 * 
	 */
	@GET
	@Path("/models/{modelId}")
	@Produces(MediaType.APPLICATION_JSON)
	@ApiOperation(value = "Searches for a model in the database. Takes the modelName as search parameter.", notes = "Searches for a model in the database.")
	@ApiResponses(value = { @ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK, model found"),
			@ApiResponse(code = HttpURLConnection.HTTP_NOT_FOUND, message = "Model could not be found."),
			@ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error") })
	public Response getModel(@PathParam("modelId") int modelId) {
		Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE, "getModel: searching for model with id " + modelId);
		Model model = null;
		Connection connection = null;
		try {
			connection = dbm.getConnection();
			model = new Model(modelId, connection);
		} catch (ModelNotFoundException e) {
			Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE, "getModel: did not find model with id " + modelId);
			return Response.status(404).entity("Model not found!").build();
		} catch (SQLException e) {
			Context.get().monitorEvent(MonitoringEvent.SERVICE_ERROR, "getModel: exception fetching model: " + e);
			logger.printStackTrace(e);
			return Response.serverError().entity("Database error!").build();
		} catch (Exception e) {
			Context.get().monitorEvent(MonitoringEvent.SERVICE_ERROR, "getModel: something went seriously wrong: " + e);
			logger.printStackTrace(e);
			return Response.serverError().entity("Server error!").build();
		} finally {
			try {
				connection.close();
			} catch (SQLException e) {
				logger.printStackTrace(e);
			}
		}
		Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE,
				"getModel: found model " + modelId + ", now converting to JSONObject and returning");
		JSONObject jsonModel = model.toJSONObject();

		return Response.ok(jsonModel.toJSONString(), MediaType.APPLICATION_JSON).build();
	}

	/**
	 * 
	 * Retrieves all model names from the database.
	 * 
	 * 
	 * @return HttpResponse containing the status code of the request and (if
	 *         the database is not empty) the model-list as a JSON array
	 * 
	 */

	@SuppressWarnings("unchecked")
	@GET
	@Path("/models/")
	@Produces(MediaType.APPLICATION_JSON)
	@ApiOperation(value = "Retrieves a list of models from the database.", notes = "Retrieves a list of all models stored in the database. Returns a list of model names.")
	@ApiResponses(value = { @ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK, model list is returned"),
			@ApiResponse(code = HttpURLConnection.HTTP_NOT_FOUND, message = "No models in the database"),
			@ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error") })
	public Response getModels() {

		ArrayList<Integer> modelIds = new ArrayList<>();
		Connection connection = null;
		try {
			connection = dbm.getConnection();
			// search for all models
			PreparedStatement statement = connection.prepareStatement("SELECT modelId FROM Model;");
			Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE, "getModels: retrieving all models..");
			ResultSet queryResult = statement.executeQuery();
			while (queryResult.next()) {
				modelIds.add(queryResult.getInt(1));
			}
			if (modelIds.isEmpty()) {
				Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE, "getModels: database is empty!");
				return Response.status(404).entity("Database is empty!").build();
			}
			connection.close();
		} catch (SQLException e) {
			Context.get().monitorEvent(MonitoringEvent.SERVICE_ERROR, "getModels: exception fetching model: " + e);
			logger.printStackTrace(e);
			return Response.serverError().entity("Database error!").build();
		} catch (Exception e) {
			Context.get().monitorEvent(MonitoringEvent.SERVICE_ERROR, "getModels: something went seriously wrong: " + e);
			logger.printStackTrace(e);
			return Response.serverError().entity("Server error!").build();
		} finally {
			try {
				connection.close();
			} catch (SQLException e) {
				logger.printStackTrace(e);
			}
		}
		Context.get().monitorEvent(MonitoringEvent.SERVICE_ERROR, "getModels: created list of models, now converting to JSONObject and returning");

		JSONArray jsonModelList = new JSONArray();
		jsonModelList.addAll(modelIds);

		return Response.ok(jsonModelList.toJSONString(), MediaType.APPLICATION_JSON).build();
	}

	@SuppressWarnings("unchecked")
	@GET
	@Path("/models/type/{modelType}")
	@Produces(MediaType.APPLICATION_JSON)
	@ApiOperation(value = "Retrieves a list of models from the database.", notes = "Retrieves a list of all models stored in the database. Returns a list of model names.")
	@ApiResponses(value = { @ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK, model list is returned"),
			@ApiResponse(code = HttpURLConnection.HTTP_NOT_FOUND, message = "No models in the database"),
			@ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error") })
	public Response getModelsByType(@PathParam("modelType") String modelType) {

		ArrayList<String> modelNames = new ArrayList<String>();
		Connection connection = null;
		try {
			connection = dbm.getConnection();
			String sql = "select `ModelAttributes`.`modelName` from `AttributeToModelAttributes`, `Attribute`, `ModelAttributes`\n" +
					"where `AttributeToModelAttributes`.`attributeId` = `Attribute`.`attributeId`\n" +
					"and `AttributeToModelAttributes`.`modelAttributesName` = `ModelAttributes`.`modelName`\n" +
					"and `Attribute`.`name` = 'type'\n" +
					"and `Attribute`.`value` = '" + modelType + "';";
			// search for all models
			PreparedStatement statement = connection.prepareStatement(sql);
			Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE, "getModels: retrieving all models..!");

			ResultSet queryResult = statement.executeQuery();
			while (queryResult.next()) {
				modelNames.add(queryResult.getString(1));
			}
			if (modelNames.isEmpty()) {
				Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE, "getModels: retrieving all models..");
				return Response.ok(new JSONArray().toJSONString(), MediaType.APPLICATION_JSON).build();
			}
			connection.close();
		} catch (SQLException e) {
			Context.get().monitorEvent(MonitoringEvent.SERVICE_ERROR, "getModels: exception fetching model: " + e);
			logger.printStackTrace(e);
			return Response.serverError().entity("Database error!").build();
		} catch (Exception e) {
			Context.get().monitorEvent(MonitoringEvent.SERVICE_ERROR, "getModels: something went seriously wrong: " + e);
			logger.printStackTrace(e);
			return Response.serverError().entity("Server error!").build();
		} finally {
			try {
				connection.close();
			} catch (SQLException e) {
				logger.printStackTrace(e);
			}
		}
		Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE,
				"getModels: created list of models, now converting to JSONObject and returning");
		JSONArray jsonModelList = new JSONArray();
		jsonModelList.addAll(modelNames);

		return Response.ok(jsonModelList.toJSONString(), MediaType.APPLICATION_JSON).build();
	}

	/**
	 * 
	 * Deletes a model.
	 *
	 * @param modelId
	 *            id of the model
	 * 
	 * @return HttpResponse containing the status code of the request
	 * 
	 */
	@DELETE
	@Path("/models/{modelId}")
	@Consumes(MediaType.TEXT_PLAIN)
	@ApiOperation(value = "Deletes a model given by its name.", notes = "Deletes a model given by its name.")
	@ApiResponses(value = { @ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK, model is deleted"),
			@ApiResponse(code = HttpURLConnection.HTTP_NOT_FOUND, message = "Model does not exist"),
			@ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error") })
	public Response deleteModel(@PathParam("modelId") int modelId) {
		Connection connection = null;
		Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE, "deleteModel: trying to delete model with id: " + modelId);
		try {
			connection = dbm.getConnection();
			Model model = new Model(modelId, connection);

			// call code generation service
			if (!codeGenerationService.isEmpty()) {
				try {
					// TODO: reactivate usage of code generation service
					// TODO: EDIT: is reactivated now, check if everything works, then the TODO can be removed
					model = callCodeGenerationService("deleteRepositoryOfModel", model, "", null);
				} catch (CGSInvocationException e) {
					return Response.serverError().entity("Model not valid: " + e.getMessage()).build();
				}
			}

			model.deleteFromDatabase(connection);
			Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE, "deleteModel: deleted model " + modelId);
			return Response.ok("Model deleted!").build();
		} catch (ModelNotFoundException e) {
			Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE, "deleteModel: did not find model with id " + modelId);
			return Response.status(404).entity("Model not found!").build();
		} catch (SQLException e) {
			Context.get().monitorEvent(MonitoringEvent.SERVICE_ERROR, "deleteModel: exception deleting model: " + e);
			logger.printStackTrace(e);
			return Response.serverError().entity("Internal server error...").build();
		} finally {
			try {
				connection.close();
			} catch (SQLException e) {
				logger.printStackTrace(e);
			}
		}
	}
	
	/**
	 * Searches for a versioned model with the given id.
	 * @param versionedModelId Id of the versioned model to search for.
	 * @return Response with status code (and possibly error message).
	 */
	@GET
	@Path("/versionedModels/{id}")
	@ApiOperation(value = "Searches for a versioned model in the database.")
	@ApiResponses(value = {
			@ApiResponse(code = HttpURLConnection.HTTP_OK, message="OK, found versioned model with the given it. Return it."),
			@ApiResponse(code = HttpURLConnection.HTTP_NOT_FOUND, message="Versioned model with the given id could not be found."),
			@ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error.")
	})
    public Response getVersionedModelById(@PathParam("id") int versionedModelId) {
		Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE,
				"getVersionedModelById: searching for versionedModel with id " + versionedModelId);
		
		Connection connection = null;
		try {
			connection = dbm.getConnection();
			
			// load versioned model by id
			VersionedModel versionedModel = new VersionedModel(versionedModelId, connection);
			
			// if no VersionedModelNotFoundException was thrown, then the model exists
			// return it
			return Response.ok(versionedModel.toJSONObject().toJSONString()).build();
		} catch (VersionedModelNotFoundException e) {
			logger.printStackTrace(e);
			return Response.status(HttpURLConnection.HTTP_NOT_FOUND)
					.entity("Versioned model with the given id could not be found.").build();
		} catch (SQLException e) {
			logger.printStackTrace(e);
			return Response.serverError().entity("Internal server error.").build();
		} finally {
			try {
			    connection.close();
			} catch (SQLException e) {
				logger.printStackTrace(e);
			}
		}		
	}
	
	/**
	 * Posts a commit to the versioned model.
	 * @param versionedModelId Id of the versioned model, where the commit should be added to.
	 * @param inputCommit Input commit as JSON, also containing the model that should be connected to the commit.
	 * @return Response with status code (and possibly error message).
	 */
	@POST
	@Path("/versionedModels/{id}/commits")
	@Consumes(MediaType.APPLICATION_JSON)
	@ApiOperation(value = "Posts a commit to the versioned model.")
	@ApiResponses(value = { @ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK, added commit to versioned model."),
			@ApiResponse(code = HttpURLConnection.HTTP_UNAUTHORIZED, message = "User is not authorized."),
			@ApiResponse(code = HttpURLConnection.HTTP_BAD_REQUEST, message = "Parse error."),
			@ApiResponse(code = HttpURLConnection.HTTP_FORBIDDEN, message = "USer is not allowed to commit to the versioned model."),
			@ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error.")
	})
	public Response postCommitToVersionedModel(@PathParam("id") int versionedModelId, String inputCommit) {
		Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE,
				"postCommitToVersionedModel: posting commit to versioned model with id " + versionedModelId);
		
		Connection connection = null;
		try {
			connection = dbm.getConnection();
			
			boolean isAnonymous = (boolean) Context.getCurrent().invoke(PROJECT_MANAGEMENT_SERVICE, "isAnonymous");
			
			if(isAnonymous) {
				return Response.status(HttpURLConnection.HTTP_UNAUTHORIZED).entity("User not authorized.").build();
			} else {
				boolean hasCommitPermission = (boolean) Context.getCurrent()
						.invoke(PROJECT_MANAGEMENT_SERVICE, "hasCommitPermission", versionedModelId);
				
				if(hasCommitPermission) {
				    // user has the permission to commit to the versioned model
					// there always exists a commit for "uncommited changes"
					// that one needs to be removed first
					VersionedModel versionedModel = new VersionedModel(versionedModelId, connection);
					Commit uncommitedChanges = versionedModel.getCommitForUncommitedChanges();
					uncommitedChanges.delete(connection);
					
					// now create a new commit
					Commit commit = new Commit(inputCommit, false);
					commit.persist(versionedModelId, connection);
					
					// now create new commit for uncommited changes
					Commit uncommitedChangesNew = new Commit(inputCommit, true);
					uncommitedChangesNew.persist(versionedModelId, connection);
					
					// reload versionedModel from database
					versionedModel = new VersionedModel(versionedModelId, connection);
					
					// get model
					Model model = commit.getModel();
					
					// do the semantic check
					if (!semanticCheckService.isEmpty()) {
						this.checkModel(model);
					}
					
					// The codegen service and metadatadocservice already require the model to have a "type" attribute
					// this "type" attribute is included in the request body
					JSONObject commitJson = (JSONObject) JSONValue.parse(inputCommit);
					String type = (String) commitJson.get("componentType");
					
					// given type "frontend" needs to be converted to "frontend-component"
					if(type.equals("frontend")) type = "frontend-component";
					// the other types "microservice" and "application" do not need to be converted
					
					// these model attributes are not persisted to the database, since model.persist already got called
					// when the commit got persisted
					model.getAttributes().add(new EntityAttribute(new SimpleEntityAttribute("syncmetaid", "type", type)));

					model.getAttributes().add(new EntityAttribute("syncmetaid", "versionedModelId", String.valueOf(versionedModelId)));
					
					// call code generation service
					if (!codeGenerationService.isEmpty()) {
						try {
							// get user input metadata doc if available
							String metadataDocString = model.getMetadataDoc();
							
							if (metadataDocString == null)
								metadataDocString = "";

							Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE, "postModel: invoking code generation service..");
							// TODO: reactivate usage of code generation service
							// TODO: EDIT: is reactivated now, check if everything works, then this TODO can be removed
							// check if it is the first commit or not
							if(versionedModel.getCommits().size() == 2) {
								// this is the first commit (there are 2 in total, because of the "uncommited changes" commit)
								model = callCodeGenerationService("createFromModel", model, metadataDocString, versionedModel);
							} else {
							    // not the first commit
								model = callCodeGenerationService("updateRepositoryOfModel", model, metadataDocString, versionedModel);
							}
						} catch (CGSInvocationException e) {
							return Response.serverError().entity("Model not valid: " + e.getMessage()).build();
						}
					}
					
					return Response.ok().build();
				} else {
					// user does not have the permission to commit to the versioned model, or an error occurred
					return Response.status(HttpURLConnection.HTTP_FORBIDDEN)
							.entity("User is not allowed to commit to the versioned model (or an error occurred).").build();
				}
			}
		} catch (SQLException e) {
	        logger.printStackTrace(e);
		    return Response.serverError().entity("Internal server error.").build();
		}/* catch (ParseException e) {
			logger.printStackTrace(e);
			return Response.status(HttpURLConnection.HTTP_BAD_REQUEST).entity("Parse error.").build();
		}*/ catch (Exception e) {
			logger.printStackTrace(e);
		    return Response.serverError().entity("Internal server error.").build();
		} finally {
			try {
			    connection.close();
			} catch (SQLException e) {
				logger.printStackTrace(e);
			}
		}
	}
	
	/**
	 * Get the status / console text of a build. The build is identified by
	 * using the queue item that is returned when a job is created.
	 * 
	 * @param queueItem
	 *            The queue item of the job
	 * @return The console text of the job
	 */

	@GET
	@Path("/deployStatus/")
	@Consumes(MediaType.TEXT_PLAIN)
	@ApiOperation(value = "Get the console text of the build from Jenkins", notes = "Get the console text of the build.")
	@ApiResponses(value = { @ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK, model will be deployed"),
			@ApiResponse(code = HttpURLConnection.HTTP_NOT_FOUND, message = "Model does not exist"),
			@ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error") })
	public Response deployStatus(@QueryParam("queueItem") String queueItem) {

		// delegate the request to the code generation service as it is the
		// service responsible for
		// Jenkins

		try {
			String answer = (String) Context.getCurrent().invoke(
					"i5.las2peer.services.codeGenerationService.CodeGenerationService@0.1", "deployStatus", queueItem);
			return Response.ok(answer).build();
		} catch (Exception e) {
			logger.printStackTrace(e);
			return Response.serverError().entity(e.getMessage()).build();
		}

	}

	/**
	 * 
	 * Requests the code generation service to start a Jenkins job for an
	 * application model.
	 * 
	 * @param versionedModelId
	 *            id of the versioned model
	 * @param jobAlias
	 *            the name/alias of the job to run, i.e. either "Build" or
	 *            "Docker"
	 * 
	 * @return HttpResponse containing the status code of the request
	 * 
	 */
	@GET
	@Path("/deploy/{versionedModelId}/{jobAlias}")
	@Consumes(MediaType.TEXT_PLAIN)
	@ApiOperation(value = "Deploys an application model.", notes = "Deploys an application model.")
	@ApiResponses(value = { @ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK, model will be deployed"),
			@ApiResponse(code = HttpURLConnection.HTTP_NOT_FOUND, message = "Model does not exist"),
			@ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error") })
	public Response deployModel(@PathParam("versionedModelId") int versionedModelId, @PathParam("jobAlias") String jobAlias) {
		Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE, "deployModel: trying to deploy versioned model with id: " + versionedModelId);
		Model model;
		Connection connection = null;

		// first parse the updated model and check for correctness of format
		try {
			connection = dbm.getConnection();
			
			// get versioned model first
			VersionedModel versionedModel = new VersionedModel(versionedModelId, connection);
			ArrayList<Commit> commits = versionedModel.getCommits();
			if(commits.size() < 2) {
				return Response.serverError().entity("There does not exist a commit to the versioned model with the given id.").build();
			}
			
			// get the commit at index 1, because the commit at index 0 is the one for uncommited changes
			Commit latestCommit = commits.get(1);
			// use the model of the latest commit for the deployment
			model = latestCommit.getModel();
			
			// add type attribute "application"
			model.getAttributes().add(new EntityAttribute(new SimpleEntityAttribute("syncmetaid", "type", "application")));
			
			// add attribute for versionedModelId
			model.getAttributes().add(new EntityAttribute(new SimpleEntityAttribute("syncmetaid", "versionedModelId", String.valueOf(versionedModelId))));

			try {

				// only create temp repository once, i.e. before the "Build"
				// job is started in Jenkins
				if (jobAlias.equals("Build")) {
					Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE, "deployModel: invoking code generation service..");
					// TODO: reactivate usage of code generation service
					// TODO: EDIT: is reactivated now, check if everything works, then TODO can be removed
					callCodeGenerationService("prepareDeploymentApplicationModel", model, "", null);
				}

				// start the jenkins job by the code generation service
				String answer = (String) Context.getCurrent().invoke(
						"i5.las2peer.services.codeGenerationService.CodeGenerationService@0.1", "startJenkinsJob",
						jobAlias);

				// safe deployment time and url
				if(!deploymentUrl.isEmpty())
					metadataDocService.updateDeploymentDetails(model, deploymentUrl);

				return Response.ok(answer).build();
			} catch (CGSInvocationException e) {
				return Response.serverError().entity("Model not valid: " + e.getMessage()).build();
			}
		} catch (Exception e) {
			Context.get().monitorEvent(MonitoringEvent.SERVICE_ERROR, "updateModel: something went seriously wrong: " + e);
			logger.printStackTrace(e);
			return Response.serverError().entity("Internal server error!").build();
		} // always close connections
		finally {
			try {
				connection.close();
			} catch (SQLException e) {
				logger.printStackTrace(e);
			}
		}
	}

	////////////////////////////////////////////////////////////////////////////////////////
	// Methods special to the CAE. Feel free to ignore them:-)
	///////////////////////////////////////////////////////////////////////////////////////

	/**
	 * 
	 * Loads a model from the database (by calling the respective resource) and
	 * sends it to the code generation service, requesting a Communication Model
	 * view to be displayed in SyncMeta's application editor view.
	 * 
	 * TODO: Not tested..
	 * 
	 * @param modelId
	 *            the id of the model to be loaded.
	 * 
	 * @return HttpResponse containing the status code of the request and the
	 *         communication view model as a JSON string
	 */
	@GET
	@Path("/models/commView/{modelId}")
	@Consumes(MediaType.APPLICATION_JSON)
	@ApiOperation(value = "Gets a CAE communication view model.", notes = "Gets a CAE communication view model.")
	@ApiResponses(value = { @ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK, model found"),
			@ApiResponse(code = HttpURLConnection.HTTP_NOT_FOUND, message = "Model does not exist"),
			@ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error") })
	public Response getCAECommunicationModel(@PathParam("modelId") int modelId) {
		// load the application model from the database
		SimpleModel appModel;
		Connection connection = null;
		try {
			connection = dbm.getConnection();
			Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE,
					"getCAECommunicationModel: Loading model " + modelId + " from the database");
			appModel = (SimpleModel) new Model(modelId, connection).getMinifiedRepresentation();
		} catch (SQLException e) {
			// model might not exist
			logger.printStackTrace(e);
			Context.get().monitorEvent(MonitoringEvent.SERVICE_ERROR, "getCAECommunicationModel: model " + modelId + " not found");
			return Response.status(404).entity("Model " + modelId + " does not exist!").build();
		} finally {
			try {
				connection.close();
			} catch (SQLException e) {
				logger.printStackTrace(e);
			}
		}
		// load submodules of application model from the database
		Serializable[] modelsToSend = null;
		for (SimpleEntityAttribute attribute : appModel.getAttributes()) {
			if (attribute.getName().equals("type") && attribute.getValue().equals("application")) {
				modelsToSend = new SimpleModel[appModel.getNodes().size() + 1];
				modelsToSend[0] = appModel; // first is always "application"
											// model itself
				int modelsToSendIndex = 1;
				// iterate through the nodes and add corresponding models to
				// array
				for (SimpleNode node : appModel.getNodes()) {
					// send application models only have one attribute with
					// its label
					// TODO: here subModelName got changed to subModelId -> check if it works
					int subModelId = Integer.valueOf(node.getAttributes().get(0).getValue());
					try {
						connection = dbm.getConnection();
						modelsToSend[modelsToSendIndex] = new Model(subModelId, connection)
								.getMinifiedRepresentation();
					} catch (SQLException e) {
						// model might not exist
						logger.printStackTrace(e);
						Context.get().monitorEvent(MonitoringEvent.SERVICE_ERROR,
								"getCAECommunicationModel: Error loading application component: " + subModelId);
						return Response.serverError().entity("Internal server error...").build();
					} finally {
						try {
							connection.close();
						} catch (SQLException e) {
							logger.printStackTrace(e);
						}
					}
					modelsToSendIndex++;
				}
				// invoke code generation service
				try {
					Serializable[] payload = { modelsToSend };

					Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE,
							"getCAECommunicationModel: Invoking code generation service now..");
					SimpleModel communicationModel = (SimpleModel) Context.getCurrent().invoke(codeGenerationService,
							"getCommunicationViewOfApplicationModel", payload);

					Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE,
							"getCAECommunicationModel: Got communication model from code generation service..");

					Model returnModel = new Model(communicationModel);
					Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE, "getCAECommunicationModel: Created model " + modelId
							+ "from simple model, now converting to JSONObject and returning");

					JSONObject jsonModel = returnModel.toJSONObject();
					return Response.ok(jsonModel.toJSONString()).build();
				} catch (Exception e) {
					Context.get().monitorEvent(MonitoringEvent.SERVICE_ERROR,
							"getCAECommunicationModel: Internal error " + e.getMessage());
					logger.printStackTrace(e);
					return Response.serverError().entity("Internal server error...").build();
				}
			}
		}
		Context.get().monitorEvent(MonitoringEvent.SERVICE_ERROR,
				"getCAECommunicationModel: model " + modelId + " is not an application");
		return Response.serverError().entity("Internal server error...").build();
	}

	/**
	 * 
	 * Calls the code generation service to see if the model is a valid CAE
	 * model. Also implements a bit of CAE logic by checking if the code
	 * generation service needs additional models (in case of an application
	 * model) and serves them automatically, such that the rest of this service
	 * does not have to deal with this "special case".
	 * 
	 * @param methodName
	 *            the method name of the code generation service
	 * @param model
	 *            {@link Model}
	 * @return the model
	 * 
	 * @throws CGSInvocationException
	 *             if something went wrong invoking the service
	 * 
	 */
	private Model callCodeGenerationService(String methodName, Model model, String metadataDoc, VersionedModel versionedModel) throws CGSInvocationException {
		
		if (metadataDoc == null)
			metadataDoc = "";
		
		Connection connection = null;
		Serializable[] modelsToSend = null;
		SimpleModel simpleModel = (SimpleModel) model.getMinifiedRepresentation();
		boolean isApplication = false;
		
	    String modelType = null;
		for(EntityAttribute a : model.getAttributes()) {
			if(a.getName().equals("type")) {
				modelType = a.getValue();
				break;
			}
		}
		
		if (modelType.equals("application")) {
			isApplication = true;
		}

		if (isApplication) {
			modelsToSend = new SimpleModel[simpleModel.getNodes().size() + 1];
			modelsToSend[0] = simpleModel; // first is always "application"
											// model itself
			int modelsToSendIndex = 1;
			// iterate through the nodes and add corresponding models to
			// array
			for (SimpleNode node : simpleModel.getNodes()) {
				// since application models only have one attribute with its
				// label
				// TODO: changed from string to int, check if it works
				String versionedModelIdStr = node.getAttributes().get(0).getValue();
				int versionedModelId = Integer.parseInt(versionedModelIdStr);
				
				// get latest commit
				VersionedModel v;
				try {
					connection = dbm.getConnection();
					v = new VersionedModel(versionedModelId, connection);
				} catch (SQLException e1) {
					throw new CGSInvocationException(e1.getMessage());
				} finally {
					try {
						connection.close();
					} catch (SQLException e) {
						logger.printStackTrace(e);
					}
				}
				ArrayList<Commit> commits = v.getCommits();
				if(commits.size() < 2) throw new CGSInvocationException("Application contains versioned model without commit.");
				
				Model m = commits.get(1).getModel();
				
				String type = "";
				if(node.getType().equals("Frontend Component")) type = "frontend-component";
				else if(node.getType().equals("Microservice")) type = "microservice";
				
				m.getAttributes().add(new EntityAttribute("syncmetaid", "type", type));
				
				logger.info("Attributes: " + node.getAttributes().toString());

				try {
					connection = dbm.getConnection();
					logger.info("Modelname: " + m.getId());
					SimpleModel s = (SimpleModel) m.getMinifiedRepresentation();
					// s now has the id of the model as id, not the versioned model id
					// thus we create a new SimpleModel and use the versioned model id as the model id
					SimpleModel s2 = new SimpleModel(String.valueOf(versionedModelId), s.getNodes(), s.getEdges(), s.getAttributes());
					modelsToSend[modelsToSendIndex] = (Serializable) s2;
				} catch (SQLException e) {
					// model might not exist
					logger.printStackTrace(e);
					throw new CGSInvocationException("Error loading application component: " + m.getId());
				} finally {
					try {
						connection.close();
					} catch (SQLException e) {
						logger.printStackTrace(e);
					}
				}
				modelsToSendIndex++;
			}
		} else {
			SimpleModel oldModel = null;
			/*try {
				connection = dbm.getConnection();
				oldModel = (SimpleModel) new Model(modelId, connection).getMinifiedRepresentation();
			} catch (SQLException e) {
				// we can ignore sql exception for the loading of the old
				// model. If such an exception is
				// thrown, we assume that
				// there is no old model
			} catch (Exception e) {
				// catch all other exceptions to ensure that the loading of
				// the old model does not influence
				// the call of the code generation service
				logger.printStackTrace(e);
			} finally {
				try {
					connection.close();
				} catch (SQLException e) {
					logger.printStackTrace(e);
				}
			}
			if (oldModel != null) {
				modelsToSend = new SimpleModel[2];
				modelsToSend[0] = simpleModel;
				modelsToSend[1] = oldModel;
			} else {
				modelsToSend = new SimpleModel[1];
				modelsToSend[0] = simpleModel;
			}*/
			
			// check if there exists an old model
			int commitCount = versionedModel.getCommits().size();
			if(commitCount == 2) {
				// there only exists one commit and the "uncommited changes" commit
				modelsToSend = new SimpleModel[1];
				modelsToSend[0] = simpleModel;
			} else {
				// there exists an old commit
				modelsToSend = new SimpleModel[2];
				modelsToSend[0] = simpleModel;
				
				Model old = versionedModel.getCommits().get(2).getModel();
				// the old model does not contain attributes for type and versionedModelId
				old.getAttributes().add(new EntityAttribute("syncmetaid", "versionedModelId", String.valueOf(versionedModel.getId())));
				
				oldModel = (SimpleModel) old.getMinifiedRepresentation();
				
				modelsToSend[1] = oldModel;
			}
		}


		// actual invocation
		try {
			String answer = "";
			if (!methodName.equals("updateRepositoryOfModel") && !methodName.equals("createFromModel")) {
				Serializable[] payload = { modelsToSend };
				answer = (String) Context.getCurrent().invoke(codeGenerationService, methodName, payload);
			} else {
				Serializable[] payload = { metadataDoc, modelsToSend };
				answer = (String) Context.getCurrent().invoke(codeGenerationService, methodName, payload);
			}

			if (!answer.equals("done")) {
				throw new CGSInvocationException(answer);
			}
			return model;
		} catch (Exception e) {
			logger.printStackTrace(e);
			throw new CGSInvocationException(e.getMessage());
		}
	}


	////////////////////////////////////////////////////////////////////////////////////////
	// Methods for Semantic Check
	////////////////////////////////////////////////////////////////////////////////////////

	/**
	 * 
	 * Performs the semantic check (if specified) on the model, without storing
	 * it
	 * 
	 * @param inputModel
	 *            the model as a JSON string
	 * 
	 * @return HttpResponse status of the check
	 * 
	 */
	@PUT
	@Path("/semantics")
	@Consumes(MediaType.APPLICATION_JSON)
	@ApiOperation(value = "Performs the semantic check", notes = "Performs the semantic check")
	@ApiResponses(value = {
			@ApiResponse(code = HttpURLConnection.HTTP_NOT_MODIFIED, message = "Semantic Check successful"),
			@ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error") })
	public Response checkModel(String inputModel) {
		Context.get().monitorEvent(MonitoringEvent.SERVICE_MESSAGE, "checkModel: performing semantic check");
		Model model;
		// first parse the updated model and check for correctness of format
		try {
			model = new Model(inputModel);
		} catch (ParseException e) {
			Context.get().monitorEvent(MonitoringEvent.SERVICE_ERROR, "semantic check: exception parsing JSON input: " + e);
			return Response.serverError().entity("JSON parsing exception, file not valid!").build();
		} catch (Exception e) {
			Context.get().monitorEvent(MonitoringEvent.SERVICE_ERROR, "semantic check: something went seriously wrong: " + e);
			logger.printStackTrace(e);
			return Response.serverError().entity("Internal server error!").build();
		}

		// do the semantic story check
		if (!semanticCheckService.isEmpty()) {
			this.doSemanticCheck(model);
		} else {
			return Response.status(400).entity("No semantic check service available").build();
		}
		return Response.ok(SemanticCheckResponse.success().toJSONResultString()).build();
	}

	private void checkModel(Model model) {
		SemanticCheckResponse result;
		EntityAttribute semcheckAttr = findSemcheckAttribute(model);
		try {
			result = (SemanticCheckResponse) Context.getCurrent().invoke(semanticCheckService, "doSemanticCheck",
					model.getMinifiedRepresentation());
		} catch (Exception e) {
			System.out.println(e);
			throw new InternalServerErrorException("could not execute semantic check service");
		}
		if (result == null) {
			throw new InternalServerErrorException("an error orrcured within the semantic check");
		} else if (result.getError() != 0) {
			if (semcheckAttr == null) {
				throw new BadRequestException(result.toJSONResultString());
			} else if (!semcheckAttr.getValue().equals("false")) {
				throw new BadRequestException("This model was supposed to be incorrect");
			}
		} else if (result.getError() == 0) {
			if (semcheckAttr != null && !semcheckAttr.getValue().equals("true")) {
				throw new BadRequestException("This model was supposed to be correct");
			}
		}
	}

	private void doSemanticCheck(Model model) {
		SemanticCheckResponse result;
		try {
			result = (SemanticCheckResponse) Context.getCurrent().invoke(semanticCheckService, "doSemanticCheck",
					model.getMinifiedRepresentation());
		} catch (Exception e) {
			System.out.println(e);
			throw new InternalServerErrorException("could not execute semantic check service");
		}
		if (result == null) {
			throw new InternalServerErrorException("an error orrcured within the semantic check");
		} else if (result.getError() != 0) {
			throw new InternalServerErrorException(Response.ok(result.toJSONResultString()).build());
		}
	}

	private EntityAttribute findSemcheckAttribute(Model model) {
		EntityAttribute res = null;
		for (EntityAttribute a : model.getAttributes()) {
			if (a.getName().equals("_semcheck")) {
				return a;
			}
		}
		return res;
	}

	////////////////////////////////////////////////////////////////////////////////////////
	// Methods providing a Swagger documentation of the service API.
	////////////////////////////////////////////////////////////////////////////////////////

	/**
	 * 
	 * Returns the API documentation for a specific annotated top level resource
	 * for purposes of the Swagger documentation.
	 * 
	 * Note: If you do not intend to use Swagger for the documentation of your
	 * Service API, this method may be removed.
	 * 
	 * Trouble shooting: Please make sure that the endpoint URL below is correct
	 * with respect to your service.
	 * 
	 * @return the resource's documentation
	 * 
	 */
	@GET
	@Path("/models/swagger.json")
	@Produces(MediaType.APPLICATION_JSON)
	public Response getSwaggerJSON() {
		Swagger swagger = new Reader(new Swagger()).read(this.getClass());
		if (swagger == null) {
			return Response.status(404).entity("Swagger API declaration not available!").build();
		}
		try {
			return Response.ok(Json.mapper().writeValueAsString(swagger), MediaType.APPLICATION_JSON).build();
		} catch (JsonProcessingException e) {
			logger.printStackTrace(e);
			return Response.serverError().entity(e.getMessage()).build();
		}
	}

	/***********METADATA DOCS*************** */

	/**
	 * Get all element to element connections in the database
	 * 
	 * @return JSON data of the list of all element to element connections
	 */
	@GET
	@Path("/docs/")
	@Produces(MediaType.APPLICATION_JSON)
	@ApiOperation(value = "Searches for all metadata docs in the database. Takes no parameter.", notes = "Searches for all metadata docs in the database.")
	@ApiResponses(value = { @ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK, Metadata doc found"),
			@ApiResponse(code = HttpURLConnection.HTTP_NOT_FOUND, message = "No metadata doc could be found."),
			@ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error") })
	public Response getDocs() {
		ArrayList<MetadataDoc> docs = null;
		ObjectMapper mapper = new ObjectMapper();

		try {
			docs = this.metadataDocService.getAll();
			String jsonString = mapper.writeValueAsString(docs);
			return Response.ok(jsonString, MediaType.APPLICATION_JSON).build();
		} catch (Exception e) {
			this.logger.printStackTrace(e);
			return Response.serverError().entity("Server error!").build();
		}
	}

	/**
	 * Get metadata docs in the database by component id
	 * 
	 * @return JSON data of the list of all metadata docs
	 */
	@GET
	@Path("/docs/component/{id}")
	@Produces(MediaType.APPLICATION_JSON)
	@ApiOperation(value = "Searches for all metadata doc in the database by component id.", notes = "Searches for all metadata doc in the database by component id.")
	@ApiResponses(value = { @ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK, metadata doc found"),
			@ApiResponse(code = HttpURLConnection.HTTP_NOT_FOUND, message = "No metadata doc could be found."),
			@ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error") })
	public Response getDocByComponentId(@PathParam("id") int id) {
		MetadataDoc doc = null;
		ObjectMapper mapper = new ObjectMapper();

		try {
			doc = this.metadataDocService.getByComponentId(id);
			String jsonString = mapper.writeValueAsString(doc);
			return Response.ok(jsonString, MediaType.APPLICATION_JSON).build();
		} catch (SQLException e) {
			this.logger.printStackTrace(e);
			return Response.ok("{}", MediaType.APPLICATION_JSON).build();
		} catch (Exception e) {
			this.logger.printStackTrace(e);
			return Response.serverError().entity("Server error!").build();
		}
	}

	/**
	 * Get metadata docs in the database by component id
	 * 
	 * @return JSON data of the list of all metadata docs
	 */
	@GET
	@Path("/docs/component/{id}/{version}")
	@Produces(MediaType.APPLICATION_JSON)
	@ApiOperation(value = "Searches for all metadata doc in the database by component id.", notes = "Searches for all metadata doc in the database by component id.")
	@ApiResponses(value = { @ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK, metadata doc found"),
			@ApiResponse(code = HttpURLConnection.HTTP_NOT_FOUND, message = "No metadata doc could be found."),
			@ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error") })
	public Response getDocByComponentIdVersion(@PathParam("id") String id, @PathParam("version") int version) {
		MetadataDoc doc = null;
		ObjectMapper mapper = new ObjectMapper();

		try {
			doc = this.metadataDocService.getByComponentIdVersion(id, version);
			String jsonString = mapper.writeValueAsString(doc);
			return Response.ok(jsonString, MediaType.APPLICATION_JSON).build();
		} catch (SQLException e) {
			this.logger.printStackTrace(e);
			return Response.ok("{}", MediaType.APPLICATION_JSON).build();
		} catch (Exception e) {
			this.logger.printStackTrace(e);
			return Response.serverError().entity("Server error!").build();
		}
	}

	/**
	 * Creates or update user input metadata doc.
	 * 
	 * @param inputJsonString json of the new model.
	 * @return HttpResponse with the status
	 */
	@POST
	@Path("/docs/{id}/{version}")
	@Consumes(MediaType.APPLICATION_JSON)
	@ApiOperation(value = "Create or update metadata doc.", notes = "Create or update metadata doc.")
	@ApiResponses(value = { @ApiResponse(code = HttpURLConnection.HTTP_CREATED, message = "OK, model stored"),
			@ApiResponse(code = HttpURLConnection.HTTP_BAD_REQUEST, message = "Input model was not valid"),
			@ApiResponse(code = HttpURLConnection.HTTP_CONFLICT, message = "Tried to save a model that already had a name and thus was not new"),
			@ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error") })
	public Response postDoc(String inputJsonString, @PathParam("version") int version, @PathParam("id") String id) {

		ObjectMapper mapper = new ObjectMapper();
		try {
			this.metadataDocService.createUpdateUserGeneratedMetadata(id, inputJsonString, version);
			return Response.ok().entity("Doc updated or created").build();
		} catch (SQLException e) {
			this.logger.printStackTrace(e);
			return Response.serverError().entity("Could not create new metadata doc, SQL exception").build();
		}
	}

	/**
	 * 
	 * Deletes a model.
	 * 
	 * @param modelName
	 *            a string containing the model name
	 * 
	 * @return HttpResponse containing the status code of the request
	 * 
	 */
	@DELETE
	@Path("/docs/{id}")
	@Consumes(MediaType.TEXT_PLAIN)
	@ApiOperation(value = "Deletes a metadata doc by id.", notes = "Deletes a metadata doc by id.")
	@ApiResponses(value = { @ApiResponse(code = HttpURLConnection.HTTP_OK, message = "OK, model is deleted"),
			@ApiResponse(code = HttpURLConnection.HTTP_NOT_FOUND, message = "Model does not exist"),
			@ApiResponse(code = HttpURLConnection.HTTP_INTERNAL_ERROR, message = "Internal server error") })
	public Response deleteDoc(@PathParam("id") String id) {
		try {
			this.metadataDocService.delete(id);
			return Response.ok().entity("element to element deleted").build();
		} catch (SQLException e) {
			this.logger.printStackTrace(e);
			return Response.serverError().entity("Could not delete metadata doc, SQL exception").build();
		}

	}
}
