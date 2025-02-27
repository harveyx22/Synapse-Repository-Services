package org.sagebionetworks.repo.web.controller;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.PrintStream;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.sagebionetworks.repo.manager.UserCertificationRequiredException;
import org.sagebionetworks.repo.manager.authentication.PasswordResetViaEmailRequiredException;
import org.sagebionetworks.repo.manager.loginlockout.UnsuccessfulLoginLockoutException;
import org.sagebionetworks.repo.manager.oauth.OAuthClientNotVerifiedException;
import org.sagebionetworks.repo.manager.password.InvalidPasswordException;
import org.sagebionetworks.repo.manager.table.InvalidTableQueryFacetColumnRequestException;
import org.sagebionetworks.repo.manager.trash.EntityInTrashCanException;
import org.sagebionetworks.repo.manager.trash.ParentInTrashCanException;
import org.sagebionetworks.repo.model.ACLInheritanceException;
import org.sagebionetworks.repo.model.AsynchJobFailedException;
import org.sagebionetworks.repo.model.BaseError;
import org.sagebionetworks.repo.model.ConflictingUpdateException;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.ErrorResponse;
import org.sagebionetworks.repo.model.ErrorResponseCode;
import org.sagebionetworks.repo.model.InvalidModelException;
import org.sagebionetworks.repo.model.LockedException;
import org.sagebionetworks.repo.model.NameConflictException;
import org.sagebionetworks.repo.model.NotReadyException;
import org.sagebionetworks.repo.model.OAuthErrorResponse;
import org.sagebionetworks.repo.model.TermsOfUseException;
import org.sagebionetworks.repo.model.TooManyRequestsException;
import org.sagebionetworks.repo.model.UnauthenticatedException;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.asynch.AsynchronousJobStatus;
import org.sagebionetworks.repo.model.auth.TwoFactorAuthErrorResponse;
import org.sagebionetworks.repo.model.drs.DrsErrorResponse;
import org.sagebionetworks.repo.model.ses.QuarantinedEmailException;
import org.sagebionetworks.repo.model.table.TableStatus;
import org.sagebionetworks.repo.model.table.TableUnavailableException;
import org.sagebionetworks.repo.queryparser.ParseException;
import org.sagebionetworks.repo.web.DeprecatedServiceException;
import org.sagebionetworks.repo.web.FileHandleLinkedException;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.repo.web.OAuthBadRequestException;
import org.sagebionetworks.repo.web.OAuthException;
import org.sagebionetworks.repo.web.OAuthForbiddenException;
import org.sagebionetworks.repo.web.OAuthUnauthenticatedException;
import org.sagebionetworks.repo.web.ServiceUnavailableException;
import org.sagebionetworks.repo.web.TemporarilyUnavailableException;
import org.sagebionetworks.repo.web.TwoFactorAuthRequiredException;
import org.sagebionetworks.repo.web.UrlHelpers;
import org.sagebionetworks.repo.web.filter.ByteLimitExceededException;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.util.NestedServletException;

import com.amazonaws.AmazonServiceException;

/**
 * This abstract class attempts to encapsulate exception handling for exceptions
 * common to all controllers.
 * <p>
 * 
 * The basic idea is that we want exception stack traces in the service log, but
 * we don't want to return them to the user. We also want to return
 * human-readable error messages to the client in a format that the client can
 * parse such as JSON. The AnnotationMethodHandlerExceptionResolver is
 * configured with the same HttpMessageConverters as the
 * AnnotationMethodHandlerAdapter and therefore should support all the same
 * encodings.
 * <p>
 * 
 * Note that the @ExceptionHandler can take an array of exception classes to all
 * be handled via the same logic and return the same HTTP status code. I chose
 * to implement them individually so that child classes can override them
 * separately and munge exception reasons as they see fit to produce a better
 * human-readable message.
 * <p>
 * 
 * Developer Note: I tried to do this as a separate controller but it did not
 * work. It seems that the exception handling methods must be <a href=
 * "http://blog.flurdy.com/2010/07/spring-mvc-exceptionhandler-needs-to-be.html"
 * >members of the controller class.</a> In this case inheritance seemed
 * appropriate, though in general we want to prefer composition over
 * inheritance. Also note that unfortunately, this only takes care of exceptions
 * thrown by our controllers. If the exception is instead thrown before or after
 * our controller logic, a different mechanism is needed to handle the
 * exception. See default error page configuration in web.xml
 * <p>
 * 
 * Here are some examples of exceptions not handled by these methods. They
 * assume message with id 4 exists:
 * <p>
 * <ul>
 * <li>returns an error page configured via web.xml since we do not have a
 * message converter configured for this encoding: curl -i -H
 * Accept:application/javascript http://localhost:8080/repo/v1/message/4.js
 * <li>returns an error page configured via web.xml since the DispatcherServlet
 * could find no applicable handler: curl -i -H Accept:application/json
 * http://localhost:8080/repo/v1/message/4/foo
 * </ul>
 * <p>
 * 
 * @author deflaux
 */
@ControllerAdvice
public class BaseControllerExceptionHandlerAdvice {

	static final String SERVICE_TEMPORARILY_UNAVAIABLE_PLEASE_TRY_AGAIN_LATER = "Service temporarily unavailable, please try again later.";
	private static Logger log = LogManager.getLogger(BaseControllerExceptionHandlerAdvice.class);
	
	/**
	 * @param ex
	 * @param request
	 * @return
	 */
	@ExceptionHandler(OAuthBadRequestException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public @ResponseBody
	OAuthErrorResponse handleOAuthBadRequestException(OAuthBadRequestException ex, HttpServletRequest request) {
		return handleOAuthException(ex, request, false);
	}

	/**
	 * @param ex
	 * @param request
	 * @return
	 */
	@ExceptionHandler(OAuthUnauthenticatedException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	public @ResponseBody
	OAuthErrorResponse handleOAuthUnauthenticatedException(OAuthUnauthenticatedException ex, HttpServletRequest request) {
		return handleOAuthException(ex, request, false);
	}

	/**
	 * @param ex
	 * @param request
	 * @return
	 */
	@ExceptionHandler(OAuthForbiddenException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public @ResponseBody
	OAuthErrorResponse handleOAuthForbiddenException(OAuthForbiddenException ex, HttpServletRequest request) {
		return handleOAuthException(ex, request, false);
	}

	/**
	 * When a TableUnavilableException occurs we need to communicate the table status to the caller with a 202 ACCEPTED,
	 * indicating we accepted they call but the resource is not ready yet.
	 * @param ex
	 * @param request
	 * @return
	 */
	@ExceptionHandler(TableUnavailableException.class)
	@ResponseStatus(HttpStatus.ACCEPTED)
	public @ResponseBody
	TableStatus handleTableUnavilableException(TableUnavailableException ex, 
			HttpServletRequest request) {
		return ex.getStatus();
	}
	
	/**
	 * When a NotReadyException occurs we need to communicate the async status to the caller with a 202 ACCEPTED,
	 * indicating we accepted they call but the resource is not ready yet.
	 * 
	 * @param ex
	 * @param request
	 * @return
	 */
	@ExceptionHandler(NotReadyException.class)
	@ResponseStatus(HttpStatus.ACCEPTED)
	public @ResponseBody
	AsynchronousJobStatus handleResultNotReadyException(NotReadyException ex, HttpServletRequest request) {
		return ex.getStatus();
	}

	@ExceptionHandler(MissingServletRequestParameterException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public @ResponseBody
	BaseError handleMissingServletRequestParameterException(MissingServletRequestParameterException ex,
			HttpServletRequest request) {
		return handleException(ex, request, false);
	}

	/**
	 * This is an application exception thrown when the request references an
	 * entity that does not exist
	 *  
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(NotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public @ResponseBody
	BaseError handleNotFoundException(NotFoundException ex,
			HttpServletRequest request) {
		return handleException(ex, request, false);
	}

	/**
	 * This exception is thrown when the service is down, or in read-only mode
	 * for non-read calls.
	 * 
	 * @param ex
	 * @param request
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(ServiceUnavailableException.class)
	@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
	public @ResponseBody
	BaseError handleServiceUnavailableException(
			ServiceUnavailableException ex, HttpServletRequest request) {
		return handleException(ex, request, true);
	}

	/**
	 * This is an application exception thrown when a resource was more recently
	 * updated than the version referenced in the current update request
	 * <p>
	 * 
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(ConflictingUpdateException.class)
	@ResponseStatus(HttpStatus.PRECONDITION_FAILED)
	public @ResponseBody
	BaseError handleConflictingUpdateException(
			ConflictingUpdateException ex, HttpServletRequest request) {
		return handleException(ex, request, false);
	}

	/**
	 * This is an application exception thrown when the user does not have
	 * sufficient authorization to perform the activity requested
	 * <p>
	 * Note that per http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html the
	 * 401 Unauthorized status code merely means that the user has not passed
	 * the data required to determine whether or not he is authorized. This is
	 * different than having the correct info and determining that the user is
	 * not authorized. Hence we return forbidden.
	 * 
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(UnauthorizedException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public @ResponseBody
	BaseError handleUnauthorizedException(UnauthorizedException ex,
			HttpServletRequest request) {
		return handleException(ex, request, false);
	}
	
	/**
	 * This is thrown when there are problems authenticating the user.
	 * The user is usually advised to correct their credentials and try again.  
	 */
	@ExceptionHandler(UnauthenticatedException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	public @ResponseBody
	BaseError handleUnauthenticatedException(UnauthenticatedException ex,
			HttpServletRequest request,
			HttpServletResponse response) {
		return handleException(ex, request, false);
	}
	
	/**
	 * This exception is thrown when an entity with a given name already exists.
	 * @param ex
	 * @param request
	 * @return
	 */
	@ExceptionHandler(NameConflictException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public @ResponseBody
	BaseError handleNameConflictException(NameConflictException ex,
			HttpServletRequest request) {
		return handleException(ex, request, false);
	}

	/**
	 * This exception is thrown when an async job fails.
	 * @param ex
	 * @param request
	 * @return
	 */
	@ExceptionHandler(AsynchJobFailedException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public @ResponseBody
	BaseError handleAsynchJobFailedException(AsynchJobFailedException ex,
			HttpServletRequest request) {
		return handleException(ex, request, false);
	}

	/**
	 * This is an application exception thrown when for example bad parameter
	 * values were passed
	 * 
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public @ResponseBody
	BaseError handleIllegalArgumentException(IllegalArgumentException ex,
			HttpServletRequest request) {
		return handleException(ex, request, false);
	}
	
	/**
	 * This is an application exception thrown when while trying to send an email
	 * the recipient is in quarantine
	 * 
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(QuarantinedEmailException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public @ResponseBody
	BaseError handleQuarantinedEmailException(QuarantinedEmailException ex,
			HttpServletRequest request) {
		return handleException(ex, request, true);
	}
	
	/**
	 * This is an application exception thrown when while trying to delete a file handle
	 * that is somehow still linked
	 * 
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(FileHandleLinkedException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	public @ResponseBody
	BaseError handleFileHandleLinkedException(FileHandleLinkedException ex, HttpServletRequest request) {
		return handleException(ex, request, false);
	}


	/**
	 * This is an application exception thrown when a model object does not pass
	 * validity checks
	 * 
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(InvalidModelException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public @ResponseBody
	BaseError handleInvalidModelException(InvalidModelException ex,
			HttpServletRequest request) {
		return handleException(ex, request, false);
	}

	/**
	 * This occurs for example when parsing a URL for an integer but a string is
	 * found in its location
	 * 
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(TypeMismatchException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public @ResponseBody
	BaseError handleTypeMismatchException(TypeMismatchException ex,
			HttpServletRequest request) {
		return handleException(ex, request, false);
	}

	/**
	 * This occurs for example when one passes field names that do not exist in
	 * the object type we are trying to deserialize
	 * 
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(JSONObjectAdapterException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public @ResponseBody
	BaseError handleJSONObjectAdapterException(JSONObjectAdapterException ex,
			HttpServletRequest request) {
		return handleException(ex, request, false);
	}

	/**
	 * This occurs when a POST or PUT request has no body
	 * 
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @return an BaseError Response object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(EOFException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public @ResponseBody
	BaseError handleEofException(EOFException ex, HttpServletRequest request) {
		return handleException(ex, request, true);
	}

	/**
	 * This occurs for example when the we send invalid JSON in the request
	 * 
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public @ResponseBody
	BaseError handleNotReadableException(
			HttpMessageNotReadableException ex, HttpServletRequest request) {
		return handleException(ex, request, false);
	}

	/**
	 * This occurs when the user specifies a query that the system cannot parse
	 * or handle
	 * 
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(ParseException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public @ResponseBody
	BaseError handleParseException(ParseException ex,
			HttpServletRequest request) {
		return handleException(ex, request, false);
	}

	/**
	 * This occurs for example when the request asks for responses to be in a
	 * content type not supported
	 * 
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
	@ResponseStatus(HttpStatus.NOT_ACCEPTABLE)
	public @ResponseBody
	BaseError handleNotAcceptableException(
			HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
		return handleException(ex, request, false);
	}

	/**
	 * Haven't been able to get this one to happen yet
	 * 
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(HttpMediaTypeNotSupportedException.class)
	@ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
	public @ResponseBody
	BaseError handleNotSupportedException(
			HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
		return handleException(ex, request, false);
	}


	/**
	 * PLFM-3574 -- throw a 400-level error when a client uses the wrong verb on
	 * an existing call
	 * @param ex the exception thrown by Spring when a method is called that isn't supported
	 * @param request the client request
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	@ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
	public @ResponseBody
	BaseError handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException ex,
															   HttpServletRequest request) {
		return handleException(ex, request, false);
	}

	/**
	 * This is thrown when there are problems persisting and retrieving objects
	 * from the datastore
	 * 
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(DatastoreException.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public @ResponseBody
	BaseError handleDatastoreException(DatastoreException ex,
			HttpServletRequest request) {
		return handleException(ex, request, true);
	}

	/**
	 * Haven't been able to get this one to happen yet. I was assuming this
	 * might catch more exceptions that I am not handling explicitly yet.
	 * 
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(ServletException.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public @ResponseBody
	BaseError handleServletException(ServletException ex,
			HttpServletRequest request) {
		return handleException(ex, request, true);
	}

	/**
	 * Haven't been able to get this one to happen yet
	 * 
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(NestedServletException.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public @ResponseBody
	BaseError handleNestedServletException(NestedServletException ex,
			HttpServletRequest request) {
		return handleException(ex, request, true);
	}

	/**
	 * Haven't been able to get this one to happen yet
	 * 
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(IllegalStateException.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public @ResponseBody
	BaseError handleIllegalStateException(IllegalStateException ex,
			HttpServletRequest request) {
		return handleException(ex, request, true);
	}

	/**
	 * When this exception occurs we want to redirect the caller to the
	 * benefactor's ACL URL.
	 * 
	 * @param ex
	 * @param request
	 * @param response
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 * @throws IOException
	 */
	@ExceptionHandler(ACLInheritanceException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public @ResponseBody
	BaseError handleAccessControlListInheritanceException(
			ACLInheritanceException ex, HttpServletRequest request,
			HttpServletResponse response) throws IOException {
		// Build and set the redirect URL
		String message = ACLInheritanceException.DEFAULT_MSG_PREFIX + UrlHelpers.createACLRedirectURL(request, ex.getBenefactorId());
		return handleException(ex, request, message, false, null);
	}

	/**
	 * Return a bit of the stack trace for NullPointerExceptions to help us
	 * debug
	 * 
	 * @param ex
	 * @param request
	 * @param response
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(NullPointerException.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public @ResponseBody
	BaseError handleNullPointerException(NullPointerException ex, HttpServletRequest request, HttpServletResponse response) {
		final int MAX_STACK_TRACE_LENGTH = 256;
		
		String trace = stackTraceToString(ex);
		
		int endIndex = (MAX_STACK_TRACE_LENGTH < trace.length()) ? MAX_STACK_TRACE_LENGTH : trace.length();
		
		String message = "Send a Jira bug report to the platform team with this message: " + trace.substring(0, endIndex);
		
		return handleException(ex, request, message, true, null);
	}

	/**
	 * Handle any exceptions not handled by specific handlers. Log an additional
	 * message with higher severity because we really do want to know what sorts
	 * of new exceptions are occurring.
	 * 
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @return an JsonEnityt object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public @ResponseBody
	BaseError handleAllOtherExceptions(Exception ex,
			HttpServletRequest request) {
		log.error("Consider specifically handling exceptions of type " + ex.getClass().getName());
		return handleException(ex, request, true);
	}
	
	/**
	 * When we encounter deadlock we do not tell the users what the error was,
	 * rather we tell them to try again later with a 503.
	 * @param ex
	 * @param request
	 * @return
	 */
	@ExceptionHandler(TransientDataAccessException.class)
	@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
	public @ResponseBody
	BaseError handleTransientDataAccessExceptions(TransientDataAccessException ex,
			HttpServletRequest request) {
		return handleException(ex, request, SERVICE_TEMPORARILY_UNAVAIABLE_PLEASE_TRY_AGAIN_LATER, true, null);
	}

	/**
	 * Log the exception at the warning level and return an OAuthErrorResponse
	 * object, which provides JSON error messages that are in-spec with OAuth2/OIDC error messages
	 *
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @param fullTrace Should the full stack trace of the exception be written to the log.
	 * @return an OAuthErrorResponse object containing the exception reason or some
	 *         other human-readable response
	 */
	OAuthErrorResponse handleOAuthException(OAuthException ex,
								 HttpServletRequest request, boolean fullTrace) {
		// Let the existing exception handler deal with logging
		handleException(ex, request, fullTrace);
		// Create the necessary object
		OAuthErrorResponse errorResponse = new OAuthErrorResponse();
		errorResponse.setError(ex.getError().name());
		errorResponse.setError_description(ex.getErrorDescription());
		if (ex.getErrorDescription() == null) {
			errorResponse.setReason(ex.getError().name());
		} else {
			errorResponse.setReason(ex.getError().name() + ". " + ex.getErrorDescription());
		}
		return errorResponse;
	}

	/**
	 * Log the exception at the warning level and return an BaseError
	 * object. Child classes should override this method if they want to change
	 * the behavior for all exceptions.
	 *
	 * @param ex        the exception to be handled
	 * @param request   the client request
	 * @param fullTrace Should the full stack trace of the exception be written to the log.
	 * @return an BaseError object containing the exception reason or some
	 * other human-readable response
	 */
	BaseError handleException(Throwable ex,
								  HttpServletRequest request, boolean fullTrace) {
		return handleException(ex, request, fullTrace, null);
	}

	/**
	 * Log the exception at the warning level and return an ErrorResponse
	 * object. Child classes should override this method if they want to change
	 * the behavior for all exceptions.
	 *
	 * @param ex                  the exception to be handled
	 * @param request             the client request
	 * @param fullTrace           Should the full stack trace of the exception be written to the log.
	 * @param associatedErrorCode Optional. Used when an ErrorResponseCode should be associated with the Throwable.
	 * @return an BaseError object containing the exception reason or some
	 * other human-readable response
	 */
	BaseError handleException(Throwable ex,
							   HttpServletRequest request, boolean fullTrace, ErrorResponseCode associatedErrorCode) {

		String message = ex.getMessage();
		if (message == null) {
			message = ex.getClass().getName();
		}
		return handleException(ex, request, message, fullTrace, associatedErrorCode);
	}

	/**
	 * Log the exception at the warning level and return an ErrorResponse object. Child classes should override this
	 * method if they want to change the behavior for all exceptions.
	 *
	 * @param ex                  the exception to be handled
	 * @param request             the client request
	 * @param fullTrace           Should the full stack trace of the exception be written to the log.
	 * @param associatedErrorCode Optional. Used when an ErrorResponseCode should be associated with the Throwable.
	 * @return BaseError object containing the exception reason or some other human-readable response
	 */
	private BaseError handleException(Throwable ex, HttpServletRequest request, String message, boolean fullTrace, ErrorResponseCode associatedErrorCode) {
		if (fullTrace) {
			// Print the full stack trace
			log.error("Handling " + ex.getClass().getSimpleName(), ex);
		} else {
			// Only print one line
			log.error("Handling {}: {}", ex.getClass().getSimpleName(), ex.getMessage());
		}

		if (!StringUtils.isEmpty(request.getPathInfo()) && request.getPathInfo().startsWith(UrlHelpers.DRS_PATH)) {
			return getDrsErrorResponse(ex, message);
		}

		ErrorResponse er = new ErrorResponse();
		er.setReason(message);
		er.setErrorCode(associatedErrorCode);
		return er;
	}

	DrsErrorResponse getDrsErrorResponse(final Throwable ex, final String message){
		final DrsErrorResponse drsErrorResponse = new DrsErrorResponse();
		drsErrorResponse.setMsg(message);
		drsErrorResponse.setStatus_code(getDrsHttpStatusCode(ex));
		return drsErrorResponse;
	}

	/**
	 * This method returns the expected http status for drs requests as specified in specification.
	 * @param ex
	 * @return int
	 */

	private long getDrsHttpStatusCode(final Throwable ex) {
		if (ex instanceof NotFoundException) {
			return HttpStatus.NOT_FOUND.value();
		} else if (ex instanceof IllegalArgumentException) {
			return HttpStatus.BAD_REQUEST.value();
		} else if (ex instanceof UnauthorizedException) {
			return HttpStatus.UNAUTHORIZED.value();
		} else if (ex instanceof UnauthenticatedException) {
			return HttpStatus.FORBIDDEN.value();
		} else {
			return HttpStatus.INTERNAL_SERVER_ERROR.value();
		}
	}

	/**
	 * @param ex
	 * @return stack trace as a string
	 */
	public static String stackTraceToString(Throwable ex) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ex.printStackTrace(new PrintStream(baos));
		try {
			baos.flush();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return baos.toString();
	}

	/**
	 * When the entity is in the trash can.
	 *
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(EntityInTrashCanException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public @ResponseBody
	BaseError handleEntityInTrashCanException(EntityInTrashCanException ex,
			HttpServletRequest request) {
		return handleException(ex, request, true);
	}
	
	/**
	 * When an entity's parent is in the trash can.
	 *
	 * @param ex
	 *            the exception to be handled
	 * @param request
	 *            the client request
	 * @return an BaseError object containing the exception reason or some
	 *         other human-readable response
	 */
	@ExceptionHandler(ParentInTrashCanException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public @ResponseBody
	BaseError handleParentInTrashCanException(ParentInTrashCanException ex,
			HttpServletRequest request) {
		return handleException(ex, request, true);
	}

	/**
	 * When the number of requests made to a particular service exceeds a threshold rate
	 */
	@ExceptionHandler(TooManyRequestsException.class)
	@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
	public @ResponseBody
	BaseError handleTooManyRequestsException(TooManyRequestsException ex,
			HttpServletRequest request, HttpServletResponse response) {
		return handleException(ex, request, true);
	}
	
	/**
	 * Handle ByteLimitExceededException which occurs when the request is 
	 * larger than the maximum size.
	 */
	@ExceptionHandler(ByteLimitExceededException.class)
	@ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
	public @ResponseBody
	BaseError handleTooManyRequestsException(ByteLimitExceededException ex,
			HttpServletRequest request, HttpServletResponse response) {
		boolean fullTrace = false;
		return handleException(ex, request, fullTrace);
	}
	
	
	
	/**
	 * This is thrown when the user has not accepted the terms of use
	 */
	@ExceptionHandler(TermsOfUseException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public @ResponseBody
	BaseError handleTermsOfUseException(TermsOfUseException ex,
			HttpServletRequest request,
			HttpServletResponse response) {
		return handleException(ex, request, false);
	}

	/**
	 * This is thrown when the requested object is being locked
	 */
	@ExceptionHandler(LockedException.class)
	@ResponseStatus(HttpStatus.LOCKED)
	public @ResponseBody
	BaseError handleLockedException(LockedException ex,

										HttpServletRequest request,
										HttpServletResponse response) {
		return handleException(ex, request, false);
	}


	/**
	 * This is thrown when the requested object is being locked
	 */
	@ExceptionHandler(UnsuccessfulLoginLockoutException.class)
	@ResponseStatus(HttpStatus.LOCKED)
	public @ResponseBody
	BaseError handleLockedException(UnsuccessfulLoginLockoutException ex,
										HttpServletRequest request,
										HttpServletResponse response) {
		return handleException(ex, request, false);
	}

	/**
	 * This is thrown when the user tries to use a deprecated service
	 */
	@ExceptionHandler(DeprecatedServiceException.class)
	@ResponseStatus(HttpStatus.GONE)
	public @ResponseBody
	BaseError handleDeprecatedServiceException(DeprecatedServiceException ex,
			HttpServletRequest request,
			HttpServletResponse response) {
		return handleException(ex, request, false);
	}


	@ExceptionHandler(UnexpectedRollbackException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public @ResponseBody
	BaseError handleUnexpectedRollbackException(UnexpectedRollbackException ex,
			HttpServletRequest request,
			HttpServletResponse response) {
		return handleException(ex.getCause(), request, true);
	}
	
	/**
	 * See PLFM-4332.  Map TemporarilyUnavailableException to 503 (service unavailable).
	 * 
	 * @param ex
	 * @param request
	 * @param response
	 * @return
	 */
	@ExceptionHandler(TemporarilyUnavailableException.class)
	@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
	public @ResponseBody
	BaseError handleTemporarilyUnavailableException(TemporarilyUnavailableException ex,
			HttpServletRequest request,
			HttpServletResponse response) {
		return handleException(ex.getCause(), request, true);
	}
	
	/**
	 * See PLFM-4292.  Map all AmazonServiceException to 502 (bad gateway) .
	 * 
	 * @param ex
	 * @param request
	 * @param response
	 * @return
	 */
	@ExceptionHandler(AmazonServiceException.class)
	@ResponseStatus(HttpStatus.BAD_GATEWAY)
	public @ResponseBody
	BaseError handleAmazonServiceException(AmazonServiceException ex,
			HttpServletRequest request,
			HttpServletResponse response) {
		return handleException(ex.getCause(), request, true);
	}

	@ExceptionHandler(InvalidPasswordException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public @ResponseBody
	BaseError handleInvalidPasswordException(InvalidPasswordException ex,
												 HttpServletRequest request) {
		return handleException(ex, request, false);
	}

	@ExceptionHandler(PasswordResetViaEmailRequiredException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	public @ResponseBody
	BaseError handlePasswordChangeRequiredException(PasswordResetViaEmailRequiredException ex,
														HttpServletRequest request){
		return handleException(ex, request, false, ErrorResponseCode.PASSWORD_RESET_VIA_EMAIL_REQUIRED);
	}

	@ExceptionHandler(UserCertificationRequiredException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public @ResponseBody
	BaseError handleUserCertificationRequiredException(UserCertificationRequiredException ex,
														HttpServletRequest request){
		return handleException(ex, request, false, ErrorResponseCode.USER_CERTIFICATION_REQUIRED);
	}

	@ExceptionHandler(InvalidTableQueryFacetColumnRequestException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public @ResponseBody
	BaseError handleInvalidTableQueryFacetColumnRequestException(InvalidTableQueryFacetColumnRequestException ex,
														   HttpServletRequest request){
		return handleException(ex, request, false, ErrorResponseCode.INVALID_TABLE_QUERY_FACET_COLUMN_REQUEST);
	}


	@ExceptionHandler(NoHandlerFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public @ResponseBody
	BaseError handleNoHandlerFoundException(NoHandlerFoundException ex, HttpServletRequest request){
		return handleException(ex,
				request,
				ex.getHttpMethod() +" was not found. Please reference API documentation at https://rest-docs.synapse.org/rest/",
				false,
				null);
	}
	
	@ExceptionHandler(OAuthClientNotVerifiedException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public @ResponseBody
	BaseError handleOAuthClientNotVerifiedException(OAuthClientNotVerifiedException ex,
														HttpServletRequest request){
		return handleException(ex, request, true, ErrorResponseCode.OAUTH_CLIENT_NOT_VERIFIED);
	}
	
	@ExceptionHandler(TwoFactorAuthRequiredException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	public @ResponseBody TwoFactorAuthErrorResponse handleTwoFactorAuthRequiredException(TwoFactorAuthRequiredException ex, HttpServletRequest request) {
		// Let the existing exception handler deal with logging
		handleException(ex, request, ex.getMessage(), false, ErrorResponseCode.TWO_FA_REQUIRED);
		
		TwoFactorAuthErrorResponse errorResponse = new TwoFactorAuthErrorResponse();
		
		errorResponse.setUserId(ex.getUserId());
		errorResponse.setTwoFaToken(ex.getTwoFaToken());
		errorResponse.setErrorCode(ErrorResponseCode.TWO_FA_REQUIRED);
		errorResponse.setReason(ex.getMessage());
		
		return errorResponse;
	}

}