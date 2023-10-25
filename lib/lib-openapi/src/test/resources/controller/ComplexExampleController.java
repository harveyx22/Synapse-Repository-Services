package controller;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.sagebionetworks.repo.model.auth.LoginResponse;
import org.sagebionetworks.repo.model.principal.AccountSetupInfo;
import org.sagebionetworks.repo.model.oauth.OAuthTokenRevocationRequest;
import org.sagebionetworks.repo.model.wiki.WikiPage;
import org.sagebionetworks.repo.web.rest.doc.ControllerInfo;
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
	public Pet getPetNoReturnDescription(@PathVariable String name) {}

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
	public LoginResponse createNewAccount(
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
	public @ResponseBody String getAcceptedResponseStatus(@PathVariable String name) {}

	/**
	 * Example of an endpoint with a response status of 'GONE'
	 *
	 * @param name a name
	 * @return the name that was added
	 */
	@ResponseStatus(HttpStatus.GONE)
	@RequestMapping(value = "/complex-pet/goneresponsestatus", method = RequestMethod.POST)
	public @ResponseBody String getGoneResponseStatus(@PathVariable String name) {}

	/**
	 * Example of an endpoint with no response status
	 *
	 * @param name a name
	 * @return the name that was added
	 */
	@RequestMapping(value = "/complex-pet/noresponsestatus", method = RequestMethod.POST)
	public @ResponseBody String getNoResponseStatus(@PathVariable String name) {}

	/**
	 * Example of a private method included in the controller
	 *
	 * @param wikiId
	 * @param wikiPage
	 */
	private static void validateUpateArguments(String wikiId, WikiPage wikiPage) {
	}

	/**
	 * Example of an endpoint that has been deprecated
	 */
	@Deprecated
	@RequestMapping(value = "/complex-pet/deprecated", method = RequestMethod.GET)
	public void getDeprecated() {}
}
