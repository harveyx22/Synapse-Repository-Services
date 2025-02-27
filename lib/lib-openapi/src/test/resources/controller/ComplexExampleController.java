package controller;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.List;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONObject;
import org.sagebionetworks.reflection.model.PaginatedResults;
import org.sagebionetworks.repo.model.AuthorizationConstants;
import org.sagebionetworks.repo.model.BooleanResult;
import org.sagebionetworks.repo.model.ListWrapper;
import org.sagebionetworks.repo.model.UserGroupHeaderResponsePage;
import org.sagebionetworks.repo.model.asynch.AsynchronousJobStatus;
import org.sagebionetworks.repo.model.form.StateEnum;
import org.sagebionetworks.repo.model.oauth.OAuthScope;
import org.sagebionetworks.repo.model.principal.AliasEnum;
import org.sagebionetworks.repo.model.schema.GetValidationSchemaResponse;
import org.sagebionetworks.repo.model.status.StatusEnum;
import org.sagebionetworks.repo.model.wiki.WikiHeader;
import org.sagebionetworks.repo.web.RequiredScope;
import org.sagebionetworks.schema.ObjectSchema;
import org.sagebionetworks.repo.model.auth.LoginResponse;
import org.sagebionetworks.repo.model.principal.AccountSetupInfo;
import org.sagebionetworks.repo.model.oauth.OAuthTokenRevocationRequest;
import org.sagebionetworks.repo.model.wiki.WikiPage;
import org.sagebionetworks.repo.web.rest.doc.ControllerInfo;
import org.sagebionetworks.schema.ObjectSchemaImpl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.sagebionetworks.openapi.pet.*;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * This controller is used to test translating for complex types.
 * @author lli
 *
 */
@ControllerInfo(displayName = "ComplexPets", path = "repo/v1")
public class ComplexExampleController {
	ConcurrentMap<String, Pet> petNameToPet = new ConcurrentHashMap<>();
	
	/**
	 * This method returns the Pet with 'name'.
	 * 
	 * @param name - the name of the pet
	 * @return the Pet associated with 'name'.
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = "/complex-pet/{petName}", method = RequestMethod.GET)
	public @ResponseBody Pet getPet(@PathVariable String petName) {
		return petNameToPet.get(petName);
	}
	
	/**
	 * Adds a dog with name 'name'.
	 * 
	 * @param name - the name of the dog
	 * @param dog - the dog object to add
	 * @return the name of the Dog that was added
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = "/complex-pet/dog/{name}", method = RequestMethod.POST)
	public @ResponseBody String addDog(@PathVariable String name, @RequestBody Poodle dog) {
		petNameToPet.put(name, dog);
		return name;
	}
	
	/**
	 * Adds a cat with name 'name'.
	 * 
	 * @param name - the name of the cat
	 * @param cat - the cat to be added
	 * @return the name of the cat that was added
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = "/complex-pet/cat/{name}", method = RequestMethod.POST)
	public @ResponseBody String addCat(@PathVariable String name, @RequestBody Cat cat) {
		petNameToPet.put(name, cat);
		return name;
	}
	
	/**
	 * Example of an endpoint that would be redirected.
	 * 
	 * @param redirect if the endpoint will redirect the client
	 */
	@RequestMapping(value = "/complex-pet/redirected", method = RequestMethod.GET)
	public void redirected(@RequestBody Boolean redirect) {}

	/**
	 * Example of an endpoint that returns void but is not redirected.
	 *
	 * @param name
	 * 	the name of the pet
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = "/complex-pet/voidreturnnoredirect/{name}", method = RequestMethod.DELETE)
	public void deleteCat(@PathVariable String name) {}

	/**
	 * Example of an endpoint that returns an object but does not have a comment description for the return
	 *
	 * @param name
	 * 	the name of the pet
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = "/complex-pet/noreturndescription/{name}", method = RequestMethod.GET)
	public Pet getPetNoReturnDescription(@PathVariable String name) {
		return new Husky();
	}

	/**
	 * Example of an endpoint with an HttpServletResponse parameter, which does not have an annotation
	 *
	 * @param fileId
	 * 	the file for the pet
	 */
	@RequestMapping(value = "/complex-pet/file/{fileId}/url/httpservletresponse", method = RequestMethod.GET)
	public void getPetFileHandleURL(
			@PathVariable String fileId,
			@RequestParam(required = false) Boolean redirect,
			HttpServletResponse response) {}

	/**
	 * Example of an endpoint with an HttpServletRequest parameter, which does not have an annotation
	 *
	 * @param name
	 * 	the name for the dog
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = "/complex-pet/dog/{name}/httpservletrequest", method = RequestMethod.DELETE)
	public void deleteDog(
			@PathVariable String name,
			HttpServletRequest request) {}

	/**
	 * Example of an endpoint with an UriComponentsBuilder parameter, which does not have an annotation
	 *
	 * @param accountSetupInfo user's first name, last name, requested user name, password, and validation token
	 * @return an access token, allowing the client to begin making authenticated requests
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = "/complex-pet/account/uricomponentsbuilder", method = RequestMethod.POST)
	@ResponseBody
	public void createNewAccount(
			@RequestBody AccountSetupInfo accountSetupInfo,
			UriComponentsBuilder uriComponentsBuilder) {}

	/**
	 * Example of an endpoint that uses an @RequestHeader annotation
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = "/complex-pet/requestheader", method = RequestMethod.POST)
	public void revokeToken(
			@RequestHeader(value = "testClientId", required=true) String verifiedClientId,
			@RequestBody OAuthTokenRevocationRequest revokeRequest) {}

	/**
	 * Example of an endpoint with a response status of 'NO_CONTENT'
	 *
	 * @param name a name
	 */
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@RequestMapping(value = "/complex-pet/nocontentresponsestatus", method = RequestMethod.POST)
	public void getNoContentResponseStatus(@PathVariable String name) {}

	/**
	 * Example of an endpoint with a response status of 'ACCEPTED'
	 *
	 * @param name a name
	 * @return the name that was added
	 */
	@ResponseStatus(HttpStatus.ACCEPTED)
	@RequestMapping(value = "/complex-pet/acceptedresponsestatus", method = RequestMethod.POST)
	public void getAcceptedResponseStatus(@PathVariable String name) {}

	/**
	 * Example of an endpoint with a response status of 'GONE'
	 *
	 * @param name a name
	 * @return the name that was added
	 */
	@ResponseStatus(HttpStatus.GONE)
	@RequestMapping(value = "/complex-pet/goneresponsestatus", method = RequestMethod.POST)
	public void getGoneResponseStatus(@PathVariable String name) {}

	/**
	 * Example of an endpoint with no response status
	 *
	 * @param name a name
	 * @return the name that was added
	 */
	@RequestMapping(value = "/complex-pet/noresponsestatus", method = RequestMethod.POST)
	public void getNoResponseStatus(@PathVariable String name) {}

	/**
	 * Example of a private method included in the controller
	 *
	 * @param wikiId
	 * @param wikiPage
	 */
	private void validateUpateArguments(String wikiId, WikiPage wikiPage) {
	}

	/**
	 * Example of a static method included in the controller
	 */
	public static void staticMethod() {
	}

	/**
	 * Example of an endpoint that has been deprecated
	 */
	@Deprecated
	@RequestMapping(value = "/complex-pet/deprecated", method = RequestMethod.GET)
	public void getDeprecated() {}

	/**
	 * Example of an endpoint where the method parameter name does not match the path variable name
	 *
	 * @param petName the name of the pet
	 * @return boolean on if the pet as a tail or not
	 */
	@ResponseStatus(HttpStatus.OK)
	@RequestMapping(value = "/complex-pet/differentpathandmethodparameternames/{petName}", method = RequestMethod.GET)
	public @ResponseBody Boolean doesPetHaveTail(
			@PathVariable(value = "petName") String nameOfPet) {
		return petNameToPet.get(nameOfPet).getHasTail();
	}

	/**
	 * Example of an endpoint with a header parameter with a value different from the method argument name
	 *
	 * @param methodArgumentName
	 */
	@RequestMapping(value = "/complex-pet/differentheaderandmethodparameternames", method = RequestMethod.GET)
	public void getHeaderName(
			@RequestHeader(value = "annotationValue") String methodArgumentName
	) { }

	/**
	 * Example of an endpoint with a request parameter with a value different from the method argument name
	 *
	 * @param methodArgumentName
	 */
	@RequestMapping(value = "/complex-pet/differentrequestparameterandmethodparameternames", method = RequestMethod.GET)
	public void getRequestParam(
			@RequestParam(value = "annotationValue") String methodArgumentName
	) { }

	/**
	 * Example of an endpoint with a regular expression in a path parameter
	 *
	 * @param id an id
	 * @return a string
	 */
	@RequestMapping(value = "/complex-pet/regularexpression/{id:.+}/test", method = RequestMethod.GET)
	public void getRegularExpression(@PathVariable String id) {}

	/**
	 * Example of an endpoint that takes a string as a parameter and returns a string
	 * @return a string
	 */
	@RequestMapping(value = "/complex-pet/string/{testString}", method = RequestMethod.GET)
	public @ResponseBody String getString(@PathVariable String testString) {
		return "test";
	}

	/**
	 * Example of an endpoint that takes an integer object as a parameter and returns an integer object
	 * @return an integer object
	 */
	@RequestMapping(value = "/complex-pet/integerclass/{testIntegerClass}", method = RequestMethod.GET)
	public @ResponseBody Integer getIntegerClass(@PathVariable Integer testIntegerClass) {
		return 1;
	}

	/**
	 * Example of an endpoint that takes a boolean object as a parameter and returns a boolean object
	 * @return a boolean object
	 */
	@RequestMapping(value = "/complex-pet/booleanclass/{testBooleanClass}", method = RequestMethod.GET)
	public @ResponseBody Boolean getBooleanClass(@PathVariable Boolean testBooleanClass) {
		return Boolean.TRUE;
	}

	/**
	 * Example of an endpoint that takes a long object as a parameter and returns a long object
	 * @return a long object
	 */
	@RequestMapping(value = "/complex-pet/longclass/{testLongClass}", method = RequestMethod.GET)
	public @ResponseBody Long getLongClass(@PathVariable Long testLongClass) {
		return 1l;
	}

	/**
	 * Example of an endpoint that takes an integer primitive as a parameter and returns an integer primitive
	 * @return an integer
	 */
	@RequestMapping(value = "/complex-pet/intprimitive/{testIntPrimitive}", method = RequestMethod.GET)
	public @ResponseBody int getBooleanClass(@PathVariable int testIntPrimitive) {
		return 1;
	}

	/**
	 * Example of an endpoint that takes a boolean primitive as a parameter and returns a boolean primitive
	 * @return a boolean
	 */
	@RequestMapping(value = "/complex-pet/booleanprimitive/{testBooleanPrimitive}", method = RequestMethod.GET)
	public @ResponseBody boolean getBooleanClass(@PathVariable boolean testBooleanPrimitive) {
		return true;
	}

	/**
	 * Example of an endpoint that takes a long primitive as a parameter and returns a long primitive
	 * @return a boolean
	 */
	@RequestMapping(value = "/complex-pet/longprimitive/{testLongPrimitive}", method = RequestMethod.GET)
	public @ResponseBody long getLongClass(@PathVariable long testLongPrimitive) {
		return 1L;
	}

	/**
	 * Example of an endpoint that takes an object as a parameter and returns an object
	 * @return an object
	 */
	@RequestMapping(value = "/complex-pet/objectclass/{testObject}", method = RequestMethod.GET)
	public @ResponseBody Object getObjectClass(@PathVariable Object testObject) {
		return new Object();
	}

	/**
	 * Example of an endpoint that returns a BooleanResult
	 * @return a BooleanResult
	 */
	@RequestMapping(value = "/complex-pet/booleanresult", method = RequestMethod.GET)
	public @ResponseBody BooleanResult getBooleanResult() {
		return new BooleanResult();
	}

	/**
	 * Example of an endpoint that takes a JSONObject as a parameter and returns a JSONObject
	 * @return a JSONObject
	 */
	@RequestMapping(value = "/complex-pet/jsonobject/{testJsonObject}", method = RequestMethod.GET)
	public @ResponseBody JSONObject getJsonObject(@PathVariable JSONObject testJsonObject) {
		return new JSONObject();
	}

	/**
	 * Example of an endpoint that returns an ObjectSchema
	 * @return an ObjectSchema
	 */
	@RequestMapping(value = "/complex-pet/objectschema", method = RequestMethod.GET)
	public @ResponseBody ObjectSchema getObjectSchema() {
		return new ObjectSchemaImpl();
	}

	/**
	 * Example of an endpoint that returns a PaginatedResult generic object type with a custom class argument
	 *
	 * @return a paginated result for a pug
	 */
	@RequestMapping(value = "/complex-pet/paginatedresultsofclass", method = RequestMethod.GET)
	public @ResponseBody PaginatedResults<Pug> getPaginatedResultsOfPug() {
		return new PaginatedResults<>();
	}

	/**
	 * Example of an endpoint that takes in a ListWrapper generic type with a custom class argument as a request body and
	 * returns a ListWrapper generic object type with a custom class argument
	 *
	 * @param petListWrapper a list of pets
	 * @return a ListWrapper result for a cat
	 */
	@RequestMapping(value = "/complex-pet/listwrapperofclass", method = RequestMethod.GET)
	public @ResponseBody ListWrapper<Cat> getListWrapperOfCat(
			@RequestBody ListWrapper<Pet> petListWrapper
	) {
		return new ListWrapper<>();
	}

	/**
	 * Example of an endpoint that takes in a List generic type query parameter
	 * and returns a List generic type with a custom class argument
	 *
	 * @param a list of terriers
	 */
	@RequestMapping(value = "/complex-pet/listofclass", method = RequestMethod.GET)
	public @ResponseBody List<Husky> getListOfHusky(
			@RequestParam List<Terrier> terriers
	) {
		return List.of(new Husky());
	}

	/**
	 * Example of an endpoint that takes in a List generic type query parameter and
	 * returns a List generic type with a String argument
	 *
	 * @param a list of strings
	 */
	@RequestMapping(value = "/complex-pet/listofstring", method = RequestMethod.GET)
	public @ResponseBody void getListOfString(
			@RequestParam List<String> strings
	) {
			}

	/**
	 * Example of an endpoint that takes in an HttpHeaders object
	 *
	 * @param headers http headers
	 */
	@RequestMapping(value = "/complex-pet/httpheaders", method = RequestMethod.GET)
	public void getHttpHeaders(
			@RequestHeader HttpHeaders headers
	) { }

	/**
	 * Example of an endpoint that has a request body and request parameter that are both not required
	 *
	 * @param a Dog
	 * @param a Cat
	 */
	@RequestMapping(value = "/complex-pet/requiredfalse", method = RequestMethod.GET)
	public @ResponseBody void getNotRequired(
			@RequestBody(required = false) Dog testRequestBodyFalse,
			@RequestParam(required = false) Cat testRequestParamFalse
	) {
	}

	/**
	 * Example of an endpoint that has a request body and request parameter that are both required
	 *
	 * @param a Pet
	 * @param a Owner
	 */
	@RequestMapping(value = "/complex-pet/requiredtrue", method = RequestMethod.GET)
	public @ResponseBody void getRequired(
			@RequestBody(required = true) Pet testRequestBodyTrue,
			@RequestParam(required = true) Owner testRequestParamTrue
	) {
	}

	/**
	 * Example of an endpoint that accepts and returns enums
	 *
	 * @param stateEnum
	 * @param aliasEnum
	 * @return a StatusEnum value
	 */
	@RequestMapping(value = "/complex-pet/enum", method = RequestMethod.GET)
	public @ResponseBody StatusEnum getEnum(
			@RequestBody StateEnum stateEnum,
			@RequestParam AliasEnum aliasEnum
			) {
		return StatusEnum.READ_WRITE;
	}

	/**
	 * Example of an endpoint with a userId parameter
	 *
	 * @param userId
	 */
	@RequestMapping(value = "/complex-pet/userid", method = RequestMethod.GET)
	public void getUserId(
			@RequestParam(value = "userId") Long userId
	) {
	}

	/**
	 * Example of an endpoint that requires authorization
	 *
	 * @param userId
	 */
	@RequiredScope({OAuthScope.view, OAuthScope.modify})
	@RequestMapping(value = "/complex-pet/authorization", method = RequestMethod.GET)
	public void getAuthorization(
			@RequestParam(value = "userId") Long userId
	) {
	}

	/**
	 * Example of an endpoint that doesn't require authorization
	 *
	 */
	@RequestMapping(value = "/complex-pet/noauthorization", method = RequestMethod.GET)
	public void getNoAuthorization() {
	}
}
