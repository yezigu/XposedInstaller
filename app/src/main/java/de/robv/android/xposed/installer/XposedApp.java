package de.robv.android.xposed.installer;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.app.Application.ActivityLifecycleCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileUtils;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v4.widget.SwipeRefreshLayout;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import de.robv.android.xposed.installer.util.AssetUtil;
import de.robv.android.xposed.installer.util.DownloadsUtil;
import de.robv.android.xposed.installer.util.InstallZipUtil;
import de.robv.android.xposed.installer.util.ModuleUtil;
import de.robv.android.xposed.installer.util.NotificationUtil;
import de.robv.android.xposed.installer.util.RepoLoader;

public class XposedApp extends Application implements ActivityLifecycleCallbacks {
    public static final String TAG = "XposedInstaller";

    @SuppressLint("SdCardPath")
    public static final String BASE_DIR = "/data/data/de.robv.android.xposed.installer/";
    public static final String ENABLED_MODULES_LIST_FILE = XposedApp.BASE_DIR + "conf/enabled_modules.list";

    private static final String[] XPOSED_PROP_FILES = new String[]{
            "/su/xposed/xposed.prop", // official systemless
            "/system/xposed.prop",    // classical
    };

    public static int WRITE_EXTERNAL_PERMISSION = 69;
    public static String THIS_APK_VERSION = "1466672400000";
    private static XposedApp mInstance = null;
    private static Thread mUiThread;
    private static Handler mMainHandler;
    private boolean mIsUiLoaded = false;
    private Activity mCurrentActivity = null;
    private SharedPreferences mPref;
    private InstallZipUtil.XposedProp mXposedProp;

    public static XposedApp getInstance() {
        return mInstance;
    }

    public static void runOnUiThread(Runnable action) {
        if (Thread.currentThread() != mUiThread) {
            mMainHandler.post(action);
        } else {
            action.run();
        }
    }

    public static void postOnUiThread(Runnable action) {
        mMainHandler.post(action);
    }

    // This method is hooked by XposedBridge to return the current version
    public static Integer getActiveXposedVersion() {
        return -1;
    }

    public static InstallZipUtil.XposedProp getXposedProp() {
        synchronized (mInstance) {
            return mInstance.mXposedProp;
        }
    }

    public static SharedPreferences getPreferences() {
        return mInstance.mPref;
    }

    public static void installApk(Context context, DownloadsUtil.DownloadInfo info) {
        Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        installIntent.setDataAndType(Uri.fromFile(new File(info.localFilename)), DownloadsUtil.MIME_TYPE_APK);
        installIntent.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.getApplicationInfo().packageName);
        context.startActivity(installIntent);
    }

    public static String getDownloadPath() {
        return getPreferences().getString("download_location", Environment.getExternalStorageDirectory() + "/XposedInstaller");
    }

    public void onCreate() {
        super.onCreate();
        mInstance = this;
        mUiThread = Thread.currentThread();
        mMainHandler = new Handler();

        mPref = PreferenceManager.getDefaultSharedPreferences(this);
        reloadXposedProp();
        createDirectories();
        NotificationUtil.init();
        AssetUtil.checkStaticBusyboxAvailability();
        AssetUtil.removeBusybox();

        registerActivityLifecycleCallbacks(this);

        @SuppressLint("SimpleDateFormat") DateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy");
        Date date = new Date();

        if (!mPref.getString("date", "").equals(dateFormat.format(date))) {
            mPref.edit().putString("date", dateFormat.format(date)).apply();

            try {
                Log.i(TAG, String.format("XposedInstaller - %s - %s", THIS_APK_VERSION, getPackageManager().getPackageInfo(getPackageName(), 0).versionName));
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
        }
    }

    private void createDirectories() {
        mkdirAndChmod("bin", 00771);
        mkdirAndChmod("conf", 00771);
        mkdirAndChmod("log", 00777);
    }

    private void mkdirAndChmod(String dir, int permissions) {
        dir = BASE_DIR + dir;
        new File(dir).mkdir();
        FileUtils.setPermissions(dir, permissions, -1, -1);
    }

    private void reloadXposedProp() {
        InstallZipUtil.XposedProp prop = null;

        for (String path : XPOSED_PROP_FILES) {
            File file = new File(path);
            if (file.canRead()) {
                FileInputStream is = null;
                try {
                    is = new FileInputStream(file);
                    prop = InstallZipUtil.parseXposedProp(is);
                    break;
                } catch (IOException e) {
                    Log.e(XposedApp.TAG, "Could not read " + file.getPath(), e);
                } finally {
                    if (is != null) {
                        try {
                            is.close();
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }

        synchronized (this) {
            mXposedProp = prop;
        }
    }

    public void updateProgressIndicator(final SwipeRefreshLayout refreshLayout) {
        final boolean isLoading = RepoLoader.getInstance().isLoading() || ModuleUtil.getInstance().isLoading();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                synchronized (XposedApp.this) {
                    if (mCurrentActivity != null) {
                        mCurrentActivity.setProgressBarIndeterminateVisibility(isLoading);
                        if (refreshLayout != null)
                            refreshLayout.setRefreshing(isLoading);
                    }
                }
            }
        });
    }

    @Override
    public synchronized void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        if (mIsUiLoaded)
            return;

        RepoLoader.getInstance().triggerFirstLoadIfNecessary();
        mIsUiLoaded = true;
    }

    @Override
    public synchronized void onActivityResumed(Activity activity) {
        mCurrentActivity = activity;
        updateProgressIndicator(null);
    }

    @Override
    public synchronized void onActivityPaused(Activity activity) {
        activity.setProgressBarIndeterminateVisibility(false);
        mCurrentActivity = null;
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity,
                                            Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
}
