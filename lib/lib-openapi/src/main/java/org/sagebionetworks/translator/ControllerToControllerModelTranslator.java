package org.sagebionetworks.translator;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic.Kind;

import org.sagebionetworks.controller.annotations.model.ControllerInfoModel;
import org.sagebionetworks.controller.annotations.model.RequestMappingModel;
import org.sagebionetworks.controller.annotations.model.ResponseStatusModel;
import org.sagebionetworks.controller.model.ControllerModel;
import org.sagebionetworks.controller.model.MethodModel;
import org.sagebionetworks.controller.model.Operation;
import org.sagebionetworks.controller.model.ParameterLocation;
import org.sagebionetworks.controller.model.ParameterModel;
import org.sagebionetworks.controller.model.RequestBodyModel;
import org.sagebionetworks.controller.model.ResponseModel;
import org.sagebionetworks.javadoc.velocity.schema.SchemaUtils;
import org.sagebionetworks.repo.model.schema.Type;
import org.sagebionetworks.repo.web.rest.doc.ControllerInfo;
import org.sagebionetworks.schema.EnumValue;
import org.sagebionetworks.schema.ObjectSchema;
import org.sagebionetworks.schema.ObjectSchemaImpl;
import org.sagebionetworks.schema.TYPE;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.JSONObjectAdapterImpl;
import org.sagebionetworks.util.ValidateArgument;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;

import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.doctree.ReturnTree;
import com.sun.source.util.DocTrees;

import jdk.javadoc.doclet.DocletEnvironment;
import jdk.javadoc.doclet.Reporter;

import static org.sagebionetworks.repo.web.PathConstants.PATH_REGEX;

/**
 * This translator pulls information from a generic doclet model into our
 * representation of a controller model. This is a layer of abstraction that is
 * then used to export the OpenAPI specification of our API.
 * 
 * @author lli
 *
 */
public class ControllerToControllerModelTranslator {
	static final Set<String> PARAMETERS_NOT_REQUIRED_TO_BE_TRANSLATED = Set.of("javax.servlet.http.HttpServletResponse",
			"org.springframework.web.util.UriComponentsBuilder", "javax.servlet.http.HttpServletRequest", "org.springframework.http.HttpHeaders");

	static final Map<String, Type> CLASS_TO_TYPE = Map.ofEntries(
			Map.entry("java.lang.String", Type.string),
			Map.entry("java.lang.Integer", Type.integer),
			Map.entry("java.lang.Boolean", Type._boolean),
			Map.entry("java.lang.Long", Type.number),
			Map.entry("java.lang.Object", Type.object),
			Map.entry("boolean", Type._boolean),
			Map.entry("int", Type.integer),
			Map.entry("long", Type.number),
			Map.entry("org.sagebionetworks.repo.model.BooleanResult", Type._boolean),
			Map.entry("org.json.JSONObject", Type.object),
			Map.entry("org.sagebionetworks.schema.ObjectSchema", Type.object));

	static final Map<String, String> CUSTOM_GENERIC_CLASS_TO_GENERIC_PROPERTY = Map.of(
			"org.sagebionetworks.reflection.model.PaginatedResults", "results",
			"org.sagebionetworks.repo.model.ListWrapper", "list"
	);

	/**
	 * Converts all controllers found in the doclet environment to controller
	 * models. Populates schemaMap based on types found in all of the controllers.
	 * 
	 * @param env                     the doclet environment being looked at
	 * @param classNameToObjectSchema a mapping between class name to object schema
	 *                                that represents it
	 * @return
	 */
	public List<ControllerModel> extractControllerModels(DocletEnvironment env, Map<String, ObjectSchema> schemaMap,
			Reporter reporter) {
		List<ControllerModel> controllerModels = new ArrayList<>();
		for (TypeElement t : getControllers(ElementFilter.typesIn(env.getIncludedElements()))) {
			ControllerModel controllerModel = translate(t, env.getDocTrees(), schemaMap, reporter);
			controllerModels.add(controllerModel);
		}
		return controllerModels;
	}

	/**
	 * Returns the controllers present in a set of files
	 * 
	 * @param files the files being examines
	 * @return a list of Controllers in the files
	 */
	List<TypeElement> getControllers(Set<TypeElement> files) {
		List<TypeElement> controllers = new ArrayList<>();
		for (TypeElement file : files) {
			if (isController(file)) {
				controllers.add(file);
			}
		}
		return controllers;
	}

	/**
	 * Determines if a file is a controller
	 * 
	 * @param file the file being examined
	 * @return true if the file is a controller, false otherwise
	 */
	boolean isController(TypeElement file) {
		ValidateArgument.required(file, "file");
		if (!file.getKind().equals(ElementKind.CLASS)) {
			return false;
		}
		List<? extends AnnotationMirror> fileAnnotations = file.getAnnotationMirrors();
		for (AnnotationMirror annotation : fileAnnotations) {
			if (ControllerInfo.class.getSimpleName().equals(getSimpleAnnotationName(annotation))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Translates a Doclet controller (TypeElement) to a ControllerModel. Populates
	 * schemaMap based on types found in the controllers
	 * 
	 * @param controller the doclet representation of a controller
	 * @param docTrees   stores the necessary javadoc comments for the methods and
	 *                   classes
	 * @param schemaMap  a mapping between class name and an ObjectSchema that
	 *                   represents that class
	 * @return a model that represents the controller.
	 */
	public ControllerModel translate(TypeElement controller, DocTrees docTrees, Map<String, ObjectSchema> schemaMap,
			Reporter reporter) {
		ControllerModel controllerModel = new ControllerModel();
		reporter.print(Kind.NOTE, "Extracting controller " + controller.getSimpleName());
		List<MethodModel> methods = getMethods(controller.getEnclosedElements(), docTrees, schemaMap, reporter);
		ControllerInfoModel controllerInfo = getControllerInfoModel(controller.getAnnotationMirrors());
		DocCommentTree controllerDocComments = docTrees.getDocCommentTree(controller);
		String controllerDescription = controllerDocComments == null ? "Auto-generated description" : getControllerDescription(controllerDocComments);
		controllerModel.withDisplayName(controllerInfo.getDisplayName()).withPath(controllerInfo.getPath())
				.withMethods(methods).withDescription(controllerDescription);
		return controllerModel;
	}

	/**
	 * Get comment for controller description.
	 * 
	 * @param controllerTree - the document tree for the controller
	 * @return the overall comment for the controller.
	 */
	String getControllerDescription(DocCommentTree controllerTree) {
		ValidateArgument.required(controllerTree, "controllerTree");
		Optional<String> comment = getBehaviorComment(controllerTree.getFullBody());
		return comment.isEmpty() ? null : comment.get();
	}

	/**
	 * Constructs a model that represents the annotations on a Controller.
	 * 
	 * @param annotations - the annotations for a controller
	 * @return a model that represents the annotations on a controller.
	 */
	ControllerInfoModel getControllerInfoModel(List<? extends AnnotationMirror> annotations) {
		ValidateArgument.required(annotations, "annotations");
		for (AnnotationMirror annotation : annotations) {
			if (!ControllerInfo.class.getSimpleName().equals(getSimpleAnnotationName(annotation))) {
				continue;
			}
			ControllerInfoModel controllerInfo = new ControllerInfoModel();
			for (ExecutableElement key : annotation.getElementValues().keySet()) {
				String keyName = key.getSimpleName().toString();
				Object value = annotation.getElementValues().get(key).getValue();
				if (keyName.equals("displayName")) {
					controllerInfo.withDisplayName(value.toString());
				} else if (keyName.equals("path")) {
					controllerInfo.withPath(value.toString());
				}
			}
			ValidateArgument.required(controllerInfo.getPath(), "controllerInfo.path");
			ValidateArgument.required(controllerInfo.getDisplayName(), "controllerInfo.displayName");
			return controllerInfo;
		}
		throw new IllegalArgumentException("ControllerInfo annotation is not present in annotations.");
	}

	/**
	 * Creates a list of MethodModel that represents all the methods in a
	 * Controller.
	 * 
	 * @param enclosedElements - list of enclosed elements inside of a Controller.
	 * @param docTrees         - tree used to get the document comment tree for a
	 *                         method.
	 * @return the created list of MethodModels
	 */
	List<MethodModel> getMethods(List<? extends Element> enclosedElements, DocTrees docTrees,
			Map<String, ObjectSchema> schemaMap, Reporter reporter) {
		List<MethodModel> methods = new ArrayList<>();
		for (ExecutableElement method : ElementFilter.methodsIn(enclosedElements)) {
			Set<Modifier> methodModifiers = method.getModifiers();
			if (methodModifiers.contains(Modifier.PRIVATE) || methodModifiers.contains(Modifier.STATIC)) {
				continue;
			}

			String methodName = method.getSimpleName().toString();
			reporter.print(Kind.NOTE, "Extracting method " + methodName);

			if (method.getAnnotation(Deprecated.class) != null) {
				reporter.print(Kind.NOTE,  String.format("Method %s has been deprecated and is not included in the OpenAPI translation.", methodName));
				continue;
			}

			DocCommentTree docCommentTree = docTrees.getDocCommentTree(method);
			Map<String, String> parameterToDescription = getParameterToDescription(docCommentTree.getBlockTags());
			Map<Class, Object> annotationToModel = getAnnotationToModel(method.getAnnotationMirrors());
			if (!annotationToModel.containsKey(RequestMapping.class)) {
				throw new IllegalStateException("Method " + methodName + " missing RequestMapping annotation.");
			}

			Optional<String> behaviorComment = getBehaviorComment(docCommentTree.getFullBody());
			Optional<RequestBodyModel> requestBody = getRequestBody(method.getParameters(), parameterToDescription,
					schemaMap);
			MethodModel methodModel = new MethodModel()
					.withPath(getMethodPath((RequestMappingModel) annotationToModel.get(RequestMapping.class)))
					.withName(methodName).withDescription(behaviorComment.isEmpty() ? null : behaviorComment.get())
					.withOperation(((RequestMappingModel) annotationToModel.get(RequestMapping.class)).getOperation())
					.withParameters(getParameters(method.getParameters(), parameterToDescription, schemaMap))
					.withRequestBody(requestBody.isEmpty() ? null : requestBody.get())
					.withResponse(getResponseModel(method, docCommentTree.getBlockTags(), annotationToModel, schemaMap))
					.withAuthenticationRequired(methodHasUserIdParameter(method));
			methods.add(methodModel);
		}
		return methods;
	}

	/**
	 * Gets a model that represents the response of a method.
	 * 
	 * @param returnType           - the return type of the method.
	 * @param returnClassName      - the full class name of the returned element.
	 * @param blockTags            - the parameter/return comments on the method.
	 * @param annotationToElements - maps an annotation to all of the elements
	 *                             inside of it.
	 * @return a model that represents the response of a method.
	 */
	ResponseModel getResponseModel(ExecutableElement method, List<? extends DocTree> blockTags,
			Map<Class, Object> annotationToModel, Map<String, ObjectSchema> schemaMap) {
		ValidateArgument.required(method, "method");
		ValidateArgument.required(blockTags, "blockTags");
		ValidateArgument.required(annotationToModel, "annotationToModel");
		ValidateArgument.required(schemaMap, "schemaMap");

		String description = getResponseDescription(blockTags, method);
		if (isRedirect(method)) {
			return generateRedirectedResponseModel(description);
		}
		return generateResponseModel(method.getReturnType(), annotationToModel, description, schemaMap);
	}
	
	/**
	 * Gets the description from a methods blocktags
	 * 
	 * @param blockTags the blocktags of the method
	 * @return the description
	 */
	String getResponseDescription(List<? extends DocTree> blockTags, ExecutableElement method) {
		Optional<String> returnComment = getReturnComment(blockTags);
		if (returnComment.isEmpty() && method.getReturnType().getKind().equals(TypeKind.VOID)) {
			return "Void";
		}
		return returnComment.orElse("Auto-generated description");
	}

	/**
	 * Generates a response model that represents when an endpoint will be redirected
	 * 
	 * @param description the description for the response.
	 * @return a response model that represents a redirected response.
	 */
	ResponseModel generateRedirectedResponseModel(String description) {
		return new ResponseModel().withDescription(description).withIsRedirected(true);
	}

	/**
	 * Generates a response model that represents an endpoint with a normal ResponseStatus
	 * 
	 * @param returnType the return type of the method
	 * @param annotationToModel a mapping between annotations to models that represent them
	 * @param description the description for the returned value
	 * @param schemaMap a mapping that we will populate which contains id's and schemas which represent those id's
	 * @return a response model that represents th response
	 */
	ResponseModel generateResponseModel(TypeMirror returnType, Map<Class, Object> annotationToModel, String description,
			Map<String, ObjectSchema> schemaMap) {
		if (!annotationToModel.containsKey(ResponseStatus.class)) {
			throw new IllegalArgumentException("Missing response status in annotationToModel.");
		}
		ResponseStatusModel responseStatus = (ResponseStatusModel) annotationToModel.get(ResponseStatus.class);

		String returnTypeSchemaId = getSchemaIdForType(returnType);
		populateSchemaMap(returnTypeSchemaId, returnType, schemaMap);
		return new ResponseModel().withDescription(description).withStatusCode(responseStatus.getStatusCode())
				.withId(returnTypeSchemaId);
	}

	/**
	 * Determines if an endpoint is being redirected
	 * 
	 * @param method the method in question
	 * @return true if the method is being redirected, false otherwise
	 */
	boolean isRedirect(ExecutableElement method) {
		boolean returnsVoid = method.getReturnType().getKind().equals(TypeKind.VOID);
		boolean containsRedirectParam = containsRedirectParam(method.getParameters());
		return returnsVoid && containsRedirectParam;
	}

	/**
	 * Determines if the method contains the 'redirect' parameter
	 * 
	 * @param params the parameters for the method in question
	 * @return true if one of the method's parameters is a boolean called "redirect", false otherwise
	 */
	boolean containsRedirectParam(List<? extends VariableElement> params) {
		for (VariableElement param : params) {
			boolean isRedirectParam = param.getSimpleName().toString().equals("redirect");
			boolean isPrimitiveBoolean = param.asType().getKind().equals(TypeKind.BOOLEAN);
			boolean isBooleanClass = param.asType().getKind().equals(TypeKind.DECLARED) && param.asType().toString().equals(Boolean.class.getName());
			boolean isBoolean = isPrimitiveBoolean || isBooleanClass;
			if (isRedirectParam && isBoolean) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Populates the schemaMap by adding an ObjectSchema that is associated with the
	 * schemaId and type.
	 *
	 * @param schemaId - the ID/Key for the type schema in the schema map
	 * @param type      - the type of the class
	 * @param schemaMap - a mapping between class names and schemas that represent
	 *                  those classes
	 */
	void populateSchemaMap(String schemaId, TypeMirror type, Map<String, ObjectSchema> schemaMap) {
		ValidateArgument.required(schemaId, "schemaId");
		ValidateArgument.required(type, "type");
		ValidateArgument.required(schemaMap, "schemaMap");

		Optional<List< ? extends TypeMirror>> genericTypeArguments = getTypeArguments(type);
		genericTypeArguments.ifPresentOrElse(arguments -> {
			TypeMirror argumentType = arguments.get(0);
			TypeKind argumentKind = argumentType.getKind();
			String argumentSchemaId = getSchemaIdForType(argumentType);
			populateSchemaMapForConcreteType(argumentSchemaId, argumentKind, schemaMap);
			populateSchemaMapForGenericType(schemaId, type, argumentType, schemaMap);
		}, () -> {
			TypeKind typeKind = type.getKind();
			if (TypeKind.DECLARED.equals(typeKind)) {
				TypeElement typeElement = (TypeElement) ((DeclaredType) type).asElement();
				if (typeElement.getKind() == ElementKind.ENUM) {
					populateSchemaMapForEnumType(schemaId, schemaMap);
				} else {
					populateSchemaMapForConcreteType(schemaId, typeKind, schemaMap);
				}
			}
		});
	}

	/**
	 * Populates the schemaMap by adding ObjectSchema that are associated with the
	 * className and type.
	 * 
	 * @param className - the name of the class
	 * @param type      - the type of the class
	 * @param schemaMap - a mapping between class names and schemas that represent
	 *                  those classes
	 */
	void populateSchemaMapForConcreteType(String className, TypeKind type, Map<String, ObjectSchema> schemaMap) {
		ValidateArgument.required(className, "className");
		ValidateArgument.required(type, "type");
		ValidateArgument.required(schemaMap, "schemaMap");

		if (!TypeKind.VOID.equals(type) && getJsonSchemaBasicTypeForClass(className).isEmpty()) {
			SchemaUtils.recursiveAddTypes(schemaMap, className, null);
		}
	}

	/**
	 * Populates the schemaMap by adding ObjectSchema that are associated with the
	 * className and type for an enum type.
	 *
	 * @param schemaId - the name of the enum
	 * @param schemaMap - a mapping between class names and schemas that represent
	 *                  those classes
	 */
	void populateSchemaMapForEnumType(String schemaId, Map<String, ObjectSchema> schemaMap) {
		ValidateArgument.required(schemaId, "schemaId");
		ValidateArgument.required(schemaMap, "schemaMap");

		ObjectSchema enumSchema = generateEnumObjectSchema(schemaId);
		schemaMap.put(schemaId, enumSchema);
	}

	/**
	 * Populates a schema map with an ObjectSchema for a generic type with parameterTypes. The ObjectSchema is identified
	 * in the schema map by the schemaId for the generic type.
	 *
	 * @param schemaId - the ID/key of the schema in the schema map
	 * @param genericType - the generic type being added to the schema map
	 * @param argumentType - the argument type from the generic type being translated
	 * @param schemaMap - the map being populated with the generic type ObjectSchema
	 */
	void populateSchemaMapForGenericType(String schemaId, TypeMirror genericType, TypeMirror argumentType, Map<String, ObjectSchema> schemaMap) {
		ValidateArgument.required(schemaId, "schemaId");
		ValidateArgument.required(genericType, "genericType");
		ValidateArgument.required(argumentType, "argumentType");
		ValidateArgument.required(schemaMap, "schemaMap");

		ObjectSchema newGenericTypeSchema;
		String genericClassName = getGenericClassName(genericType);

		if ("java.util.List".equals(genericClassName)) {
			String typeParameterClass = argumentType.toString();
			newGenericTypeSchema = generateArrayObjectSchema(typeParameterClass);
			newGenericTypeSchema.setId(schemaId);
		} else if (CUSTOM_GENERIC_CLASS_TO_GENERIC_PROPERTY.containsKey(genericClassName)) {
			newGenericTypeSchema = generateObjectSchemaForGenericType(schemaId, genericClassName, argumentType);
		} else {
			throw new UnsupportedOperationException(String.format("Generic class %s is not supported by the OpenAPI translator", genericClassName));
		}

		schemaMap.put(schemaId, newGenericTypeSchema);
	}

	/**
	 * Determines the schema ID for a type
	 *
	 * @param typeMirror - the type
	 * @return the schema ID
	 */
	String getSchemaIdForType(TypeMirror typeMirror) {
		ValidateArgument.required(typeMirror, "typeMirror");

		String schemaId;
		Optional<List< ? extends TypeMirror>> genericTypeArguments = getTypeArguments(typeMirror);
		if (genericTypeArguments.isEmpty()) {
			schemaId = typeMirror.toString();
		} else {
			schemaId = getSchemaIdForGenericType(typeMirror);
		}
		return schemaId;
	}

	/**
	 * Determines the schema ID for a generic type
	 *
	 * @param genericType - the generic type
	 * @return the schema ID
	 */
	String getSchemaIdForGenericType(TypeMirror genericType) {
		ValidateArgument.required(genericType, "genericType");

		String genericClassSimpleName;
		String parameterClassSimpleName;

		String genericClassName = getGenericClassName(genericType);
		String parameterClassName = getTypeArguments(genericType).get().get(0).toString();

		try {
			Class genericClazz = Class.forName(genericClassName);
			Class parameterClazz = Class.forName(parameterClassName);
			genericClassSimpleName = genericClazz.getSimpleName();
			parameterClassSimpleName = parameterClazz.getSimpleName();
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}

		return String.format("%sOf%s", genericClassSimpleName, parameterClassSimpleName);
	}

	/**
	 * Extracts the type arguments from a type. Returns an empty list if the type is not a parameterized type.
	 *
	 * @param typeMirror - the type
	 * @return  an optional of a list of type arguments
	 */
	Optional<List< ? extends TypeMirror>> getTypeArguments(TypeMirror typeMirror) {
		ValidateArgument.required(typeMirror, "typeMirror");

		List< ? extends TypeMirror> resultList = new ArrayList<>();
		if (typeMirror instanceof DeclaredType) {
			DeclaredType declaredType = (DeclaredType) typeMirror;
			resultList = declaredType.getTypeArguments();
		}

		return resultList.isEmpty() ? Optional.empty() : Optional.of(resultList);
	}

	/**
	 * Generates an object schema for a generic type with a type argument
	 *
	 * @param schemaId - the schema ID for the generic type
	 * @param genericClassName - the name of the generic type's class
	 * @param argumentType - the type argument associated with the generic type
	 * @return a schema map for the generic type
	 */
	ObjectSchema generateObjectSchemaForGenericType(String schemaId, String genericClassName, TypeMirror argumentType) {
		ValidateArgument.required(schemaId, "schemaId");
		ValidateArgument.required(genericClassName, "genericClassName");
		ValidateArgument.required(argumentType, "argumentType");

		ObjectSchema genericAndArgumentSchema = new ObjectSchemaImpl();

		String typeParameterName = argumentType.toString();

		ObjectSchema genericTypeSchema;
		try {
			Class genericClazz = Class.forName(genericClassName);
			Field objectSchemaField = genericClazz.getField("EFFECTIVE_SCHEMA");
			String schemaString = (String) objectSchemaField.get(null);
			JSONObjectAdapterImpl adapter = new JSONObjectAdapterImpl(schemaString);
			genericTypeSchema = new ObjectSchemaImpl(adapter);
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		} catch (NoSuchFieldException e) {
			throw new RuntimeException(e);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		} catch (JSONObjectAdapterException e) {
			throw new RuntimeException(e);
		}

		String genericProperty = CUSTOM_GENERIC_CLASS_TO_GENERIC_PROPERTY.get(genericClassName);

		genericAndArgumentSchema.setType(TYPE.OBJECT);
		genericAndArgumentSchema.setId(schemaId);
		genericAndArgumentSchema.setProperties((LinkedHashMap<String, ObjectSchema>) genericTypeSchema.getProperties());
		ObjectSchema genericPropertySchema = generateArrayObjectSchema(typeParameterName);
		genericAndArgumentSchema.putProperty(genericProperty, genericPropertySchema);

		return genericAndArgumentSchema;
	}

	/**
	 * Generates an object schema for an array
	 *
	 * @param typeName - the name of the type in the array
	 * @return an ObjectSchema for an array
	 */
	ObjectSchema generateArrayObjectSchema(String typeName) {
		ValidateArgument.required(typeName, "typeName");

		ObjectSchema arraySchema = new ObjectSchemaImpl();
		ObjectSchema itemsSchema = new ObjectSchemaImpl();
		arraySchema.setType(TYPE.ARRAY);
		Optional<Type> basicType = getJsonSchemaBasicTypeForClass(typeName);
		basicType.ifPresentOrElse(type -> {
			itemsSchema.setType(TYPE.getTypeFromJSONValue(basicType.get().toString()));
		}, () -> {
			itemsSchema.setId(typeName);
			itemsSchema.setType(TYPE.OBJECT);
		});
		arraySchema.setItems(itemsSchema);

		return  arraySchema;
	}

	/**
	 * Generates an object schema for an enum
	 *
	 * @param enumClassName - the type
	 * @return an ObjectSchema for an enum
	 */
	ObjectSchema generateEnumObjectSchema(String enumClassName) {
		ValidateArgument.required(enumClassName, "enumClassName");

		ObjectSchema enumSchema = new ObjectSchemaImpl();
		enumSchema.setType(TYPE.STRING);
		enumSchema.setName(enumClassName);

		Class enumClazz;
		try {
			enumClazz = Class.forName(enumClassName);
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}

		Object[] enumConstants = enumClazz.getEnumConstants();
		EnumValue[] enumValues = new EnumValue[enumConstants.length];
		for (int i = 0; i < enumConstants.length; i++) {
			enumValues[i] = new EnumValue(enumConstants[i].toString());
		}
		enumSchema.setEnum(enumValues);

		return  enumSchema;
	}

	/**
	 * Determines the class name for a parameterized generic type
	 *
	 * @param genericType - the generic type
	 * @return the class name
	 */
	String getGenericClassName(TypeMirror genericType) {
		ValidateArgument.required(genericType, "genericType");

		DeclaredType declaredType = (DeclaredType) genericType;
		TypeElement typeElement = (TypeElement) declaredType.asElement();
		return typeElement.getQualifiedName().toString();
	}

	static Optional<Type> getJsonSchemaBasicTypeForClass(String id){
		return Optional.ofNullable(CLASS_TO_TYPE.get(id));
	}

	/**
	 * Get the path that this method represents.
	 * 
	 * @param requestMapping - model of the RequestMapping annotation
	 * @return the path that this method represents.
	 */
	String getMethodPath(RequestMappingModel requestMapping) {
		ValidateArgument.required(requestMapping, "RequestMapping");
		ValidateArgument.required(requestMapping.getPath(), "RequestMapping.path");
		return requestMapping.getPath().replaceAll(PATH_REGEX, "").replace("*", "");
	}

	/**
	 * Constructs a map that maps an annotation class to a model that represents
	 * that annotation.
	 * 
	 * @param methodAnnotations - all of the annotation present on a method.
	 * @return map of an annotation class to model for that annotation.
	 */
	Map<Class, Object> getAnnotationToModel(List<? extends AnnotationMirror> methodAnnotations) {
		ValidateArgument.required(methodAnnotations, "Method annotations");
		Map<Class, Object> annotationToModel = new LinkedHashMap<>();
		for (AnnotationMirror annotation : methodAnnotations) {
			String annotationName = getSimpleAnnotationName(annotation);
			if (annotationName.equals("RequestMapping")) {
				annotationToModel.put(RequestMapping.class, getRequestMappingModel(annotation));
			} else if (annotationName.equals("ResponseStatus")) {
				annotationToModel.put(ResponseStatus.class, getResponseStatusModel(annotation));
			}
		}

		if (!annotationToModel.containsKey(ResponseStatus.class)) {
			annotationToModel.put(ResponseStatus.class, new ResponseStatusModel().withStatusCode(200));
		}

		return annotationToModel;
	}

	/**
	 * Constructs a model that represents the ResponseStatus annotation.
	 * 
	 * @param annotation the annotation being looked at
	 * @return a model that represents the ResponseStatus annotation.
	 */
	ResponseStatusModel getResponseStatusModel(AnnotationMirror annotation) {
		ValidateArgument.required(annotation, "Annotation");
		ResponseStatusModel responseStatus = new ResponseStatusModel();
		for (ExecutableElement key : annotation.getElementValues().keySet()) {
			String keyName = key.getSimpleName().toString();
			if (keyName.equals("value") || keyName.equals("code")) {
				responseStatus.withStatusCode(
						getHttpStatusCode(annotation.getElementValues().get(key).getValue().toString()));
			}
		}
		return responseStatus;
	}

	/**
	 * Constructs a model that represents a RequestMapping annotation.
	 * 
	 * @param annotation the annotation being looked at
	 * @return a model that represents a RequestMapping annotation.
	 */
	RequestMappingModel getRequestMappingModel(AnnotationMirror annotation) {
		ValidateArgument.required(annotation, "Annotation");
		RequestMappingModel requestMapping = new RequestMappingModel();
		for (ExecutableElement key : annotation.getElementValues().keySet()) {
			String keyName = key.getSimpleName().toString();
			if (keyName.equals("value") || keyName.equals("path")) {
				requestMapping.withPath(annotation.getElementValues().get(key).getValue().toString());
			} else if (keyName.equals("method")) {
				String value = annotation.getElementValues().get(key).getValue().toString();
				String[] parts = value.split("\\.");
				requestMapping.withOperation(Operation.get(RequestMethod.valueOf(parts[parts.length - 1])));
			}
		}
		return requestMapping;
	}

	/**
	 * Get the HttpStatus of an endpoint
	 * 
	 * @param object - the status
	 * @return HttpStatus of an endpoint.
	 */
	int getHttpStatusCode(String object) {
		HttpStatus status = HttpStatus.valueOf(object);
		switch (status) {
			case OK:
				return HttpStatus.OK.value();
			case CREATED:
				return HttpStatus.CREATED.value();
			case NO_CONTENT:
				return  HttpStatus.NO_CONTENT.value();
			case ACCEPTED:
				return  HttpStatus.ACCEPTED.value();
			case GONE:
				return  HttpStatus.GONE.value();
			default:
				throw new IllegalArgumentException("Could not translate HttpStatus for status " + status);
		}
	}

	/**
	 * Constructs a model that represents the request body for a method.
	 * 
	 * @param parameters             - the parameters of this method.
	 * @param parameterToDescription - maps a parameter name to that parameters
	 *                               description
	 * @return optional that stores a model that represents the request body, or
	 *         empty if a request body does not exist.
	 */
	Optional<RequestBodyModel> getRequestBody(List<? extends VariableElement> parameters,
			Map<String, String> paramToDescription, Map<String, ObjectSchema> schemaMap) {
		for (VariableElement param : parameters) {
			TypeMirror parameterType = param.asType();
			if (PARAMETERS_NOT_REQUIRED_TO_BE_TRANSLATED.contains(parameterType.toString())) {
				continue;
			}
			AnnotationMirror paramAnnotation = getParameterAnnotation(param);
			String simpleAnnotationName = getSimpleAnnotationName(paramAnnotation);
			if (RequestBody.class.getSimpleName().equals(simpleAnnotationName)) {
				String paramName = param.getSimpleName().toString();
				String paramDescription = paramToDescription.get(paramName);
				String paramTypeSchemaId = getSchemaIdForType(parameterType);
				populateSchemaMap(paramTypeSchemaId, parameterType, schemaMap);
				boolean paramIsRequired = isParameterRequired(paramAnnotation);

				return Optional.of(new RequestBodyModel().withDescription(paramDescription).withRequired(paramIsRequired)
						.withId(paramTypeSchemaId));
			}
		}
		return Optional.empty();
	}

	/**
	 * Constructs a list representing all of the parameters for a method (excluding
	 * parameter present in RequestBody).
	 * 
	 * @param params                 - the parameters of the method.
	 * @param parameterToDescription - maps a parameter name to a description of
	 *                               that parameter.
	 * @return a list that represents all parameters for a method.
	 */
	List<ParameterModel> getParameters(List<? extends VariableElement> params,
			Map<String, String> parameterToDescription, Map<String, ObjectSchema> schemaMap) {
		List<ParameterModel> parameters = new ArrayList<>();
		for (VariableElement param : params) {
			TypeMirror parameterType = param.asType();
			if (PARAMETERS_NOT_REQUIRED_TO_BE_TRANSLATED.contains(parameterType.toString())) {
				continue;
			}
			ParameterLocation paramLocation = getParameterLocation(param);
			if (paramLocation == null) {
				continue;
			}

			AnnotationMirror paramAnnotation = getParameterAnnotation(param);
			String paramName = param.getSimpleName().toString();

			for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> elements: paramAnnotation.getElementValues().entrySet()) {
				if ("value".equals(elements.getKey().getSimpleName().toString())) {
					paramName = elements.getValue().getValue().toString();
				}
			}

			if (ParameterLocation.query.equals(paramLocation) && "userId".equals(paramName)) {
				continue;
			}

			boolean paramIsRequired = isParameterRequired(paramAnnotation);
			String paramTypeSchemaId = getSchemaIdForType(parameterType);
			populateSchemaMap(paramTypeSchemaId, parameterType, schemaMap);

			String paramDescription = parameterToDescription.get(paramName);
			parameters.add(new ParameterModel().withDescription(paramDescription).withIn(paramLocation)
					.withName(paramName).withRequired(paramIsRequired).withId(paramTypeSchemaId));
		}
		return parameters;
	}

	/**
	 * Get if a parameter is required
	 *
	 * @param paramAnnotation - the parameter being looked at
	 * @return if the parameter is required
	 */
	boolean isParameterRequired(AnnotationMirror paramAnnotation) {
		ValidateArgument.required(paramAnnotation, "paramAnnotation");

		for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> elements: paramAnnotation.getElementValues().entrySet()) {
			if ("required".equals(elements.getKey().getSimpleName().toString())) {
				return (Boolean) elements.getValue().getValue();
			}
		}

		return true;
	}

	/**
	 * Get the location of a parameter in the HTTP request.
	 * 
	 * @param param - the parameter being looked at
	 * @return location of a parameter, null if it is the RequestBody annotation.
	 */
	ParameterLocation getParameterLocation(VariableElement param) {
		String simpleAnnotationName = getSimpleAnnotationName(getParameterAnnotation(param));
		if (PathVariable.class.getSimpleName().equals(simpleAnnotationName)) {
			return ParameterLocation.path;
		}
		if (RequestParam.class.getSimpleName().equals(simpleAnnotationName)) {
			return ParameterLocation.query;
		}
		if (RequestHeader.class.getSimpleName().equals(simpleAnnotationName)) {
			return ParameterLocation.header;
		}
		if (RequestBody.class.getSimpleName().equals(simpleAnnotationName)) {
			return null;
		}
		throw new IllegalArgumentException("Unable to get parameter location with annotation " + simpleAnnotationName);
	}

	/**
	 * Get the annotation for a parameter.
	 * 
	 * @param param - the parameter being looked at
	 * @return annotation for the parameter
	 */
	AnnotationMirror getParameterAnnotation(VariableElement param) {
		ValidateArgument.required(param, "Param");
		List<? extends AnnotationMirror> annotations = param.getAnnotationMirrors();
		if (annotations.size() != 1) {
			throw new IllegalArgumentException(
					String.format("Each method parameter should have one annotation, %s has %d", param.getSimpleName().toString(), annotations.size())
					);
		}
		return annotations.get(0);
	}

	/**
	 * Get the simple name for an annotation.
	 * 
	 * @param annotation - the annotation being looked at
	 * @return the simple name for the annotation.
	 */
	String getSimpleAnnotationName(AnnotationMirror annotation) {
		return annotation.getAnnotationType().asElement().getSimpleName().toString();
	}

	/**
	 * Constructs a map that maps a parameter name to a description of that
	 * parameter.
	 * 
	 * @param blockTags - list of param/return comments on the method.
	 * @return map that maps a parameter name to a description of that parameter.
	 */
	Map<String, String> getParameterToDescription(List<? extends DocTree> blockTags) {
		Map<String, String> parameterToDescription = new HashMap<>();
		if (blockTags == null) {
			return parameterToDescription;
		}
		for (DocTree comment : blockTags) {
			if (!comment.getKind().equals(DocTree.Kind.PARAM)) {
				continue;
			}
			ParamTree paramComment = (ParamTree) comment;
			if (!paramComment.getDescription().isEmpty()) {
				parameterToDescription.put(paramComment.getName().toString(), paramComment.getDescription().toString());
			}

		}
		return parameterToDescription;
	}

	/**
	 * Get the return comment for a method.
	 * 
	 * @param blockTags - list of param/return comments on the method.
	 * @return return optional containing comment for a method, or empty optional if
	 *         there is none.
	 */
	Optional<String> getReturnComment(List<? extends DocTree> blockTags) {
		if (blockTags == null) {
			return Optional.empty();
		}
		for (DocTree comment : blockTags) {
			if (comment.getKind().equals(DocTree.Kind.RETURN)) {
				ReturnTree returnComment = (ReturnTree) comment;
				if (returnComment.getDescription().isEmpty()) {
					return Optional.empty();
				}
				return Optional.of(returnComment.getDescription().toString());
			}
		}
		return Optional.empty();
	}

	/**
	 * Gets an optional that contains the behavior/overall comment.
	 * 
	 * @param fullBody - the body comment.
	 * @return optional with the behavior/overall comment inside, or empty optional
	 *         if no behavior comment found.
	 */
	Optional<String> getBehaviorComment(List<? extends DocTree> fullBody) {
		if (fullBody == null || fullBody.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(fullBody.toString());
	}


	/**
	 * Determines if a method takes in parameter userId
	 *
	 * @param method the method
	 *
	 * @return whether the method contains parameter userId
	 */
	Boolean methodHasUserIdParameter(ExecutableElement method) {
		ValidateArgument.required(method, "method");
		for (VariableElement param : method.getParameters()) {
			TypeMirror parameterType = param.asType();
			if (PARAMETERS_NOT_REQUIRED_TO_BE_TRANSLATED.contains(parameterType.toString())) {
				continue;
			}

			ParameterLocation paramLocation = getParameterLocation(param);
			AnnotationMirror paramAnnotation = getParameterAnnotation(param);
			String paramName = param.getSimpleName().toString();
			for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> elements: paramAnnotation.getElementValues().entrySet()) {
				if ("value".equals(elements.getKey().getSimpleName().toString())) {
					paramName = elements.getValue().getValue().toString();
				}
			}

			if (ParameterLocation.query.equals(paramLocation) && "userId".equals(paramName)) {
				return true;
			}
		}
		return false;
	}
}