package controllers.publix.workers;

import controllers.publix.IPublix;
import controllers.publix.MTGroupChannel;
import controllers.publix.Publix;
import controllers.publix.StudyAssets;
import daos.common.ComponentResultDao;
import daos.common.StudyResultDao;
import daos.common.worker.MTWorkerDao;
import exceptions.publix.BadRequestPublixException;
import exceptions.publix.PublixException;
import general.common.StudyLogger;
import models.common.Batch;
import models.common.Component;
import models.common.Study;
import models.common.StudyResult;
import models.common.workers.MTSandboxWorker;
import models.common.workers.MTWorker;
import play.Logger;
import play.Logger.ALogger;
import play.db.jpa.JPAApi;
import play.mvc.Result;
import services.publix.PublixErrorMessages;
import services.publix.PublixHelpers;
import services.publix.ResultCreator;
import services.publix.WorkerCreator;
import services.publix.idcookie.IdCookieModel;
import services.publix.idcookie.IdCookieService;
import services.publix.workers.MTErrorMessages;
import services.publix.workers.MTPublixUtils;
import services.publix.workers.MTStudyAuthorisation;
import utils.common.HttpUtils;
import utils.common.JsonUtils;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Implementation of JATOS' public API for studies that are started via MTurk. A
 * MTurk run is done by a MTWorker or a MTSandboxWorker.
 *
 * @author Kristian Lange
 */
@Singleton
public class MTPublix extends Publix<MTWorker> implements IPublix {

    private static final ALogger LOGGER = Logger.of(MTPublix.class);

    /**
     * URL query parameter used by MT
     */
    public static final String ASSIGNMENT_ID = "assignmentId";

    /**
     * Hint: Don't confuse MTurk's workerId with JATOS' workerId. They aren't
     * the same. MTurk's workerId is generated by MTurk and stored within the
     * MTWorker. MTurk's workerId is used to identify a worker in MTurk. JATOS'
     * workerId is used to identify a worker in JATOS.
     */
    public static final String MT_WORKER_ID = "workerId";

    private final MTPublixUtils publixUtils;
    private final MTStudyAuthorisation studyAuthorisation;
    private final ResultCreator resultCreator;
    private final WorkerCreator workerCreator;
    private final MTErrorMessages errorMessages;
    private final MTWorkerDao mtWorkerDao;
    private final StudyLogger studyLogger;

    @Inject
    MTPublix(JPAApi jpa, MTPublixUtils publixUtils,
            MTStudyAuthorisation studyAuthorisation,
            ResultCreator resultCreator, WorkerCreator workerCreator,
            MTGroupChannel groupChannel, IdCookieService idCookieService,
            MTErrorMessages errorMessages, StudyAssets studyAssets,
            JsonUtils jsonUtils, ComponentResultDao componentResultDao,
            StudyResultDao studyResultDao, MTWorkerDao mtWorkerDao, StudyLogger studyLogger) {
        super(jpa, publixUtils, studyAuthorisation,
                groupChannel, idCookieService,
                errorMessages, studyAssets, jsonUtils, componentResultDao,
                studyResultDao, studyLogger);
        this.publixUtils = publixUtils;
        this.studyAuthorisation = studyAuthorisation;
        this.resultCreator = resultCreator;
        this.workerCreator = workerCreator;
        this.errorMessages = errorMessages;
        this.mtWorkerDao = mtWorkerDao;
        this.studyLogger = studyLogger;
    }

    @Override
    public Result startStudy(Long studyId, Long batchId) throws PublixException {
        // Get MTurk query parameters
        String mtWorkerId = HttpUtils.getQueryString(MT_WORKER_ID);
        String mtAssignmentId = HttpUtils.getQueryString(ASSIGNMENT_ID);
        LOGGER.info(".startStudy: studyId " + studyId + ", " + "batchId "
                + batchId);

        Study study = publixUtils.retrieveStudy(studyId);
        Batch batch = publixUtils.retrieveBatchByIdOrDefault(batchId, study);
        // Check if it's just a preview coming from MTurk. We don't allow
        // previews.
        if (mtAssignmentId != null
                && mtAssignmentId.equals("ASSIGNMENT_ID_NOT_AVAILABLE")) {
            // It's a preview coming from Mechanical Turk -> no previews
            throw new BadRequestPublixException(errorMessages.noPreviewAvailable(studyId));
        }

        // Check worker and create if doesn't exists
        if (mtWorkerId == null) {
            throw new BadRequestPublixException(
                    MTErrorMessages.NO_MTURK_WORKERID);
        }
        MTWorker worker = mtWorkerDao.findByMTWorkerId(mtWorkerId);
        if (worker == null) {
            String workerType = retrieveWorkerTypeFromQueryString();
            boolean isRequestFromMTurkSandbox = workerType
                    .equals(MTSandboxWorker.WORKER_TYPE);
            worker = workerCreator.createAndPersistMTWorker(mtWorkerId,
                    isRequestFromMTurkSandbox, batch);
        }
        studyAuthorisation.checkWorkerAllowedToStartStudy(worker, study, batch);
        LOGGER.info(".startStudy: study (study ID " + studyId + ", batch ID "
                + batchId + ") " + "assigned to worker with ID "
                + worker.getId());

        publixUtils.finishAbandonedStudyResults();
        StudyResult studyResult = resultCreator.createStudyResult(study, batch, worker);
        publixUtils.setUrlQueryParameter(studyResult);
        idCookieService.writeIdCookie(worker, batch, studyResult);

        Component firstComponent = publixUtils.retrieveFirstActiveComponent(study);
        studyLogger.log(study, "Started study run with " + MTWorker.UI_WORKER_TYPE
                + " worker", batch, worker);
        return redirect(controllers.publix.routes.PublixInterceptor.startComponent(
                studyId, firstComponent.getId(), studyResult.getId()));
    }

    @Override
    public Result finishStudy(Long studyId, Long studyResultId,
            Boolean successful, String errorMsg) throws PublixException {
        LOGGER.info(".finishStudy: studyId " + studyId + ", " + "studyResultId "
                + studyResultId + ", " + "successful " + successful + ", "
                + "errorMsg \"" + errorMsg + "\"");
        IdCookieModel idCookie = idCookieService.getIdCookie(studyResultId);
        Study study = publixUtils.retrieveStudy(studyId);
        Batch batch = publixUtils.retrieveBatch(idCookie.getBatchId());
        MTWorker worker = publixUtils
                .retrieveTypedWorker(idCookie.getWorkerId());
        studyAuthorisation.checkWorkerAllowedToDoStudy(worker, study, batch);

        StudyResult studyResult = publixUtils.retrieveStudyResult(worker, study,
                studyResultId);
        String confirmationCode;
        if (!PublixHelpers.studyDone(studyResult)) {
            confirmationCode = publixUtils.finishStudyResult(successful,
                    errorMsg, studyResult);
            publixUtils.finishMemberInGroup(studyResult);
            groupChannel.closeGroupChannel(studyResult);
        } else {
            confirmationCode = studyResult.getConfirmationCode();
        }
        idCookieService.discardIdCookie(studyResult.getId());
        studyLogger.log(study, "Finished study run", worker);

        if (HttpUtils.isAjax()) {
            return ok(confirmationCode);
        } else {
            if (!successful) {
                return ok(views.html.publix.error.render(errorMsg));
            } else {
                return ok(views.html.publix.confirmationCode
                        .render(confirmationCode));
            }
        }
    }

    private String retrieveWorkerTypeFromQueryString()
            throws BadRequestPublixException {
        String mtWorkerId = HttpUtils.getQueryString(MTPublix.MT_WORKER_ID);
        if (mtWorkerId != null) {
            return retrieveWorkerType();
        }
        throw new BadRequestPublixException(
                PublixErrorMessages.UNKNOWN_WORKER_TYPE);
    }

    /**
     * Returns either MTSandboxWorker.WORKER_TYPE or MTWorker.WORKER_TYPE. It
     * depends on the URL query string. If the quera has the key turkSubmitTo
     * and it's value contains 'sandbox' it returns the MTSandboxWorker one -
     * and MTWorker one otherwise.
     */
    public String retrieveWorkerType() {
        String turkSubmitTo = request().getQueryString("turkSubmitTo");
        if (turkSubmitTo != null
                && turkSubmitTo.toLowerCase().contains("sandbox")) {
            return MTSandboxWorker.WORKER_TYPE;
        } else {
            return MTWorker.WORKER_TYPE;
        }
    }

}
