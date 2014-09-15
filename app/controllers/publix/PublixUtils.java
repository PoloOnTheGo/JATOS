package controllers.publix;

import java.io.StringWriter;
import java.util.List;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import models.ComponentModel;
import models.StudyModel;
import models.results.ComponentResult;
import models.results.ComponentResult.ComponentState;
import models.results.StudyResult;
import models.results.StudyResult.StudyState;
import models.workers.Worker;

import org.w3c.dom.Document;

import play.Logger;
import play.db.jpa.JPA;
import play.mvc.Http.RequestBody;
import services.ErrorMessages;
import services.Persistance;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.net.MediaType;

import exceptions.BadRequestPublixException;
import exceptions.ForbiddenPublixException;
import exceptions.NotFoundPublixException;
import exceptions.PublixException;
import exceptions.UnsupportedMediaTypePublixException;

/**
 * Utilility class with functions that are common for all classes that extend
 * Publix and don't belong in a controller.
 * 
 * @author madsen
 */
public abstract class PublixUtils<T extends Worker> {

	private static final String CLASS_NAME = PublixUtils.class.getSimpleName();

	private ErrorMessages<T> errorMessages;

	public PublixUtils(ErrorMessages<T> errorMessages) {
		this.errorMessages = errorMessages;
	}

	public ComponentResult startComponent(ComponentModel component,
			StudyResult studyResult) throws ForbiddenPublixException {
		return startComponent(component, studyResult, MediaType.HTML_UTF_8);
	}

	/**
	 * Start or restart a component
	 */
	public ComponentResult startComponent(ComponentModel component,
			StudyResult studyResult, MediaType errorMediaType)
			throws ForbiddenPublixException {
		ComponentResult componentResult = retrieveComponentResult(component,
				studyResult);
		if (componentResult != null) {
			// Only one component of the same kind can be done in the same study
			// by the same worker. Exception: If a component is reloadable,
			// the old component result will be deleted and a new one generated.
			if (component.isReloadable()) {
				Persistance.removeComponentResult(componentResult);
			} else {
				// Worker tried to reload a non-reloadable component -> end
				// study and component with fail
				componentResult.setComponentState(ComponentState.FAIL);
				componentResult.merge();
				exceptionalFinishStudy(studyResult);
				throw new ForbiddenPublixException(
						ErrorMessages.componentNotAllowedToReload(studyResult
								.getStudy().getId(), component.getId()));
			}
		}
		// Only one ComponentResult can be in state started at the same time.
		// To start a new ComponentResult, finish all other ones.
		finishAllComponentResults(studyResult);
		return Persistance.createComponentResult(studyResult, component);
	}

	public String finishStudy(Boolean successful, StudyResult studyResult) {
		finishAllComponentResults(studyResult);
		String confirmationCode;
		if (successful) {
			confirmationCode = studyResult.generateConfirmationCode();
			studyResult.setStudyState(StudyState.FINISHED);
		} else {
			confirmationCode = "fail";
			studyResult.setStudyState(StudyState.FAIL);
		}
		studyResult.merge();
		Publix.session().remove(MAPublix.MECHARG_TRY);
		return confirmationCode;
	}

	public void exceptionalFinishStudy(StudyResult studyResult) {
		finishStudy(false, studyResult);
		// Since an exception triggers a transaction rollback we have
		// to commit the transaction manually.
		if (JPA.em().getTransaction().isActive()) {
			JPA.em().flush();
			JPA.em().getTransaction().commit();
		}
	}

	public void finishAllComponentResults(StudyResult studyResult) {
		for (ComponentResult componentResult : studyResult
				.getComponentResultList()) {
			if (!(componentResult.getComponentState() == ComponentState.FINISHED || componentResult
					.getComponentState() == ComponentState.FAIL)) {
				componentResult.setComponentState(ComponentState.FINISHED);
				componentResult.merge();
			}
		}
	}

	public static String getRequestBodyAsString(RequestBody requestBody) {
		String text = requestBody.asText();
		if (text != null) {
			return text;
		}

		JsonNode json = requestBody.asJson();
		if (json != null) {
			return json.toString();
		}

		Document xml = requestBody.asXml();
		if (xml != null) {
			return asString(xml);
		}

		return null;
	}

	/**
	 * Convert XML-Document to String
	 */
	public static String asString(Document doc) {
		try {
			DOMSource domSource = new DOMSource(doc);
			StringWriter writer = new StringWriter();
			StreamResult result = new StreamResult(writer);
			TransformerFactory tf = TransformerFactory.newInstance();
			Transformer transformer = tf.newTransformer();
			transformer.transform(domSource, result);
			return writer.toString();
		} catch (TransformerException e) {
			Logger.info(CLASS_NAME + ".asString: XML to String conversion: ", e);
			return null;
		}
	}

	public abstract T retrieveWorker() throws PublixException;

	public abstract T retrieveWorker(MediaType errorMediaType)
			throws PublixException;

	public StudyResult retrieveWorkersStartedStudyResult(T worker,
			StudyModel study) throws ForbiddenPublixException {
		return retrieveWorkersStartedStudyResult(worker, study,
				MediaType.HTML_UTF_8);
	}

	public StudyResult retrieveWorkersStartedStudyResult(T worker,
			StudyModel study, MediaType errorMediaType)
			throws ForbiddenPublixException {
		for (StudyResult studyResult : worker.getStudyResultList()) {
			if (studyResult.getStudy().getId() == study.getId()
					&& studyResult.getStudyState() == StudyState.STARTED) {
				// Since there is only one study result of the same study
				// allowed to be in STARTED, return the first one
				return studyResult;
			}
		}
		// Worker never started the study
		throw new ForbiddenPublixException(
				errorMessages.workerNeverStartedStudy(worker, study.getId()),
				errorMediaType);
	}

	public StudyResult retrieveWorkersLastStudyResult(T worker, StudyModel study)
			throws ForbiddenPublixException {
		return retrieveWorkersLastStudyResult(worker, study,
				MediaType.HTML_UTF_8);
	}

	public StudyResult retrieveWorkersLastStudyResult(T worker,
			StudyModel study, MediaType errorMediaType)
			throws ForbiddenPublixException {
		StudyResult studyResult;
		int studyResultListSize = worker.getStudyResultList().size();
		for (int i = (studyResultListSize - 1); i >= 0; i--) {
			studyResult = worker.getStudyResultList().get(i);
			if (studyResult.getStudy().getId() == study.getId()) {
				return studyResult;
			}
		}
		throw new ForbiddenPublixException(errorMessages.workerNeverDidStudy(
				worker, study.getId()), errorMediaType);
	}

	public ComponentResult retrieveComponentResult(ComponentModel component,
			StudyResult studyResult) {
		for (ComponentResult componentResult : studyResult
				.getComponentResultList()) {
			if (componentResult.getComponent().getId() == component.getId()) {
				return componentResult;
			}
		}
		return null;
	}

	public ComponentResult retrieveStartedComponentResult(
			ComponentModel component, StudyResult studyResult,
			ComponentState maxAllowedComponentState)
			throws ForbiddenPublixException {
		return retrieveStartedComponentResult(component, studyResult,
				maxAllowedComponentState, MediaType.HTML_UTF_8);
	}

	public ComponentResult retrieveStartedComponentResult(
			ComponentModel component, StudyResult studyResult,
			ComponentState maxAllowedComponentState, MediaType errorMediaType)
			throws ForbiddenPublixException {
		ComponentResult componentResult = retrieveComponentResult(component,
				studyResult);
		if (componentResult == null) {
			// If component was never started, conveniently start it
			componentResult = startComponent(component, studyResult,
					errorMediaType);
		}
		if (componentResult.getComponentState() == ComponentState.FINISHED
				|| componentResult.getComponentState() == ComponentState.FAIL) {
			throw new ForbiddenPublixException(
					ErrorMessages.componentAlreadyFinishedOrFailed(component
							.getStudy().getId(), component.getId()),
					errorMediaType);
		}
		if (componentResult.getComponentState().ordinal() > maxAllowedComponentState
				.ordinal()) {
			// Restart component
			componentResult = startComponent(component, studyResult,
					errorMediaType);
		}
		return componentResult;
	}

	public ComponentModel retrieveLastComponent(StudyResult studyResult) {
		List<ComponentResult> componentResultList = studyResult
				.getComponentResultList();
		if (componentResultList.size() > 0) {
			return componentResultList.get(componentResultList.size() - 1)
					.getComponent();
		}
		return null;
	}

	public ComponentModel retrieveFirstActiveComponent(StudyModel study)
			throws NotFoundPublixException {
		ComponentModel component = study.getFirstComponent();
		// Find first active component or null if study has no active components
		while (component != null && !component.isActive()) {
			component = study.getNextComponent(component);
		}
		if (component == null) {
			throw new NotFoundPublixException(
					ErrorMessages.studyHasNoActiveComponents(study.getId()));
		}
		return component;
	}

	public ComponentModel retrieveNextActiveComponent(StudyResult studyResult) {
		ComponentModel currentComponent = retrieveLastComponent(studyResult);
		ComponentModel nextComponent = studyResult.getStudy().getNextComponent(
				currentComponent);
		// Find next active component or null if study has no more components
		while (nextComponent != null && !nextComponent.isActive()) {
			nextComponent = studyResult.getStudy().getNextComponent(
					nextComponent);
		}
		return nextComponent;
	}

	public ComponentModel retrieveComponent(StudyModel study, Long componentId)
			throws NotFoundPublixException, BadRequestPublixException,
			ForbiddenPublixException {
		return retrieveComponent(study, componentId, MediaType.HTML_UTF_8);
	}

	public ComponentModel retrieveComponent(StudyModel study, Long componentId,
			MediaType errorMediaType) throws NotFoundPublixException,
			BadRequestPublixException, ForbiddenPublixException {
		ComponentModel component = ComponentModel.findById(componentId);
		if (component == null) {
			throw new NotFoundPublixException(
					ErrorMessages.componentNotExist(study.getId(), componentId),
					errorMediaType);
		}
		if (!component.getStudy().getId().equals(study.getId())) {
			throw new BadRequestPublixException(
					ErrorMessages.componentNotBelongToStudy(study.getId(),
							componentId), errorMediaType);
		}
		if (!component.isActive()) {
			throw new ForbiddenPublixException(
					ErrorMessages.componentNotActive(study.getId(),
							componentId), errorMediaType);
		}
		return component;
	}

	public StudyModel retrieveStudy(Long studyId)
			throws NotFoundPublixException {
		return retrieveStudy(studyId, MediaType.HTML_UTF_8);
	}

	public StudyModel retrieveStudy(Long studyId, MediaType errorMediaType)
			throws NotFoundPublixException {
		StudyModel study = StudyModel.findById(studyId);
		if (study == null) {
			throw new NotFoundPublixException(
					ErrorMessages.studyNotExist(studyId), errorMediaType);
		}
		return study;
	}

	public String getDataFromRequestBody(RequestBody requestBody,
			ComponentModel component)
			throws UnsupportedMediaTypePublixException {
		return getDataFromRequestBody(requestBody, component,
				MediaType.HTML_UTF_8);
	}

	public String getDataFromRequestBody(RequestBody requestBody,
			ComponentModel component, MediaType errorMediaType)
			throws UnsupportedMediaTypePublixException {
		String data = getRequestBodyAsString(requestBody);
		if (data == null) {
			throw new UnsupportedMediaTypePublixException(
					ErrorMessages.submittedDataUnknownFormat(component
							.getStudy().getId(), component.getId()),
					errorMediaType);
		}
		return data;
	}

	public static String getUrlWithRequestQueryString(String requestUrl,
			String url) {
		int queryBegin = requestUrl.lastIndexOf("?");
		if (queryBegin > 0) {
			String queryString = requestUrl.substring(queryBegin + 1);
			url = url + "?" + queryString;
		}
		return url;
	}

}
