package publix.controllers;

import java.io.File;
import java.io.IOException;

import models.ComponentModel;
import play.Logger;
import play.Play;
import play.libs.F.Promise;
import play.libs.ws.WS;
import play.mvc.Controller;
import play.mvc.Result;
import utils.ControllerUtils;
import utils.IOUtils;

import com.google.inject.Singleton;
import common.Common;

/**
 * Manages web-access to files in the external study assets directories (outside
 * of JATOS' packed Jar).
 * 
 * @author Kristian Lange
 */
@Singleton
public class StudyAssets extends Controller {

	/**
	 * Identifying part of any URL that indicates an access to the study assets
	 * directories.
	 */
	public static final String URL_STUDY_ASSETS = "study_assets";

	private static final String CLASS_NAME = StudyAssets.class.getSimpleName();

	/**
	 * Property name in application config for the path in the file system to
	 * the study assets root directory, the directory where all study assets are
	 * located
	 */
	private static final String PROPERTY_STUDY_ASSETS_ROOT_PATH = "jatos.studyAssetsRootPath";

	/**
	 * Default path in the file system to the study assets root directory in
	 * case it wasn't specified in the config
	 */
	private static final String DEFAULT_STUDY_ASSETS_ROOT_PATH = "study_assets_root";

	/**
	 * Path in the file system to the study assets root directory. If the
	 * property is defined in the configuration file then use it as the base
	 * path. If property isn't defined, try in default study path instead.
	 */
	public static final String STUDY_ASSETS_ROOT_PATH = getStudyAssetsRootPath();

	private static String getStudyAssetsRootPath() {
		String tempStudyAssetsRootPath = Play.application().configuration()
				.getString(PROPERTY_STUDY_ASSETS_ROOT_PATH);
		if (tempStudyAssetsRootPath != null
				&& !tempStudyAssetsRootPath.trim().isEmpty()) {
			// Replace ~ with actual home directory
			tempStudyAssetsRootPath = tempStudyAssetsRootPath.replace("~",
					System.getProperty("user.home"));
			// Replace Unix-like file separator with actual system's one
			tempStudyAssetsRootPath = tempStudyAssetsRootPath.replace("/",
					File.separator);
		} else {
			tempStudyAssetsRootPath = DEFAULT_STUDY_ASSETS_ROOT_PATH;
		}

		// If relative path add JATOS' base path as prefix
		if (!tempStudyAssetsRootPath.startsWith(File.separator)) {
			tempStudyAssetsRootPath = Common.BASEPATH + File.separator
					+ tempStudyAssetsRootPath;
		}
		Logger.info(CLASS_NAME + ": Path to study assets directory is "
				+ tempStudyAssetsRootPath);
		return tempStudyAssetsRootPath;
	}

	/**
	 * Action called while routing. Translates the given file path from the URL
	 * into a file path of the OS's file system and returns the file.
	 */
	public Result at(String filePath) {
		File file;
		try {
			filePath = filePath.replace("/", File.separator);
			file = IOUtils.getExistingFileSecurely(STUDY_ASSETS_ROOT_PATH,
					filePath);
			Logger.info(CLASS_NAME + ".at: loading file " + file.getPath()
					+ ".");
		} catch (IOException e) {
			Logger.info(CLASS_NAME + ".at: failed loading from path "
					+ STUDY_ASSETS_ROOT_PATH + File.separator + filePath);
			String errorMsg = "Resource \"" + filePath
					+ "\" couldn't be found.";
			if (ControllerUtils.isAjax()) {
				return notFound(errorMsg);
			} else {
				return notFound(views.html.error.render(errorMsg));
			}
		}
		return ok(file, true);
	}

	public static String getComponentUrlPath(String studyAssetsDirName,
			ComponentModel component) {
		return "/" + URL_STUDY_ASSETS + "/" + studyAssetsDirName + "/"
				+ component.getHtmlFilePath();
	}

	/**
	 * Generates an URL with protocol HTTP. Takes the hostname from the request,
	 * the url's path from the given urlPath, and the query string again from
	 * the request.
	 */
	public static String getUrlWithQueryString(String oldUri,
			String requestHost, String newUrlPath) {
		// Check if we have an query string (begins with '?')
		int queryBegin = oldUri.lastIndexOf("?");
		if (queryBegin > 0) {
			String queryString = oldUri.substring(queryBegin + 1);
			newUrlPath = newUrlPath + "?" + queryString;
		}

		// It would be nice if Play has a way to find out which protocol it
		// uses. Apparently it changes http automatically into https if it uses
		// encryption (at least when I checked with Play 2.2.3).
		return "http://" + requestHost + newUrlPath;
	}

	/**
	 * Like an internal redirect or an proxy. The URL in the browser doesn't
	 * change.
	 */
	public Promise<Result> forwardTo(String url) {
		return WS.url(url).get().map(response -> {
			// Prevent browser from caching pages - this would be an
			// security issue and additionally confuse the study flow
				response().setHeader("Cache-control", "no-cache, no-store");
				return ok(response.getBody()).as("text/html");
			});
	}

}
