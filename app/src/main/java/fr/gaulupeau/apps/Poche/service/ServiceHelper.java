package fr.gaulupeau.apps.Poche.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.core.util.Consumer;

import fr.gaulupeau.apps.Poche.data.Settings;
import fr.gaulupeau.apps.Poche.network.Updater;

import wallabag.apiwrapper.WallabagService;

public class ServiceHelper {

    private static final String TAG = ServiceHelper.class.getSimpleName();

    public static void syncAndUpdate(Context context, Settings settings,
                                     Updater.UpdateType updateType, boolean auto) {
        syncAndUpdate(context, settings, updateType, auto, null);
    }

    private static void syncAndUpdate(Context context, Settings settings,
                                      Updater.UpdateType updateType,
                                      boolean auto, Long operationID) {
        Log.d(TAG, "syncAndUpdate() started");

        if(settings != null && settings.isOfflineQueuePending()) {
            Log.d(TAG, "syncAndUpdate() running sync and update");

            ActionRequest syncRequest = getSyncQueueRequest(auto, false);
            syncRequest.setNextRequest(getUpdateArticlesRequest(settings, updateType, auto, operationID));

            startService(context, syncRequest);
        } else {
            updateArticles(context, settings, updateType, auto, operationID);
        }
    }

    public static void syncQueue(Context context) {
        syncQueue(context, false, false);
    }

    public static void syncQueue(Context context, boolean auto) {
        syncQueue(context, auto, false);
    }

    public static void syncQueue(Context context, boolean auto, boolean byOperation) {
        Log.d(TAG, "syncQueue() started");

        startService(context, getSyncQueueRequest(auto, byOperation));
    }

    private static ActionRequest getSyncQueueRequest(boolean auto, boolean byOperation) {
        ActionRequest request = new ActionRequest(ActionRequest.Action.SYNC_QUEUE);
        if(auto) request.setRequestType(ActionRequest.RequestType.AUTO);
        else if(byOperation) request.setRequestType(ActionRequest.RequestType.MANUAL_BY_OPERATION);

        return request;
    }

    public static void updateArticles(Context context, Settings settings,
                                      Updater.UpdateType updateType,
                                      boolean auto, Long operationID) {
        Log.d(TAG, "updateArticles() started");

        startService(context, getUpdateArticlesRequest(settings, updateType, auto, operationID));
    }

    private static ActionRequest getUpdateArticlesRequest(Settings settings,
                                                          Updater.UpdateType updateType,
                                                          boolean auto, Long operationID) {
        ActionRequest request = new ActionRequest(ActionRequest.Action.UPDATE_ARTICLES);
        request.setUpdateType(updateType);
        request.setOperationID(operationID);
        if(auto) request.setRequestType(ActionRequest.RequestType.AUTO);

        if(updateType == Updater.UpdateType.FAST && settings.isSweepingAfterFastSyncEnabled()) {
            request.setNextRequest(getSweepDeletedArticlesRequest(auto, operationID));
        }

        if(settings.isImageCacheEnabled()) {
            addNextRequest(request, getFetchImagesRequest());
        }

        return request;
    }

    public static void sweepDeletedArticles(Context context) {
        Log.d(TAG, "sweepDeletedArticles() started");

        startService(context, getSweepDeletedArticlesRequest(false, null));
    }

    private static ActionRequest getSweepDeletedArticlesRequest(boolean auto, Long operationID) {
        ActionRequest request = new ActionRequest(ActionRequest.Action.SWEEP_DELETED_ARTICLES);
        request.setOperationID(operationID);
        if(auto) request.setRequestType(ActionRequest.RequestType.AUTO);

        return request;
    }

    public static void downloadArticleAsFile(Context context, int articleID,
                                             WallabagService.ResponseFormat downloadFormat,
                                             Long operationID) {
        Log.d(TAG, "downloadArticleAsFile() started; download format: " + downloadFormat);

        ActionRequest request = new ActionRequest(ActionRequest.Action.DOWNLOAD_AS_FILE);
        request.setArticleID(articleID);
        request.setDownloadFormat(downloadFormat);
        request.setOperationID(operationID);

        startService(context, request);
    }

    public static void fetchImages(Context context) {
        Log.d(TAG, "fetchImages() started");

        startService(context, getFetchImagesRequest());
    }

    private static ActionRequest getFetchImagesRequest() {
        return new ActionRequest(ActionRequest.Action.FETCH_IMAGES);
    }

    private static void addNextRequest(ActionRequest actionRequest, ActionRequest nextRequest) {
        while(actionRequest.getNextRequest() != null) actionRequest = actionRequest.getNextRequest();

        actionRequest.setNextRequest(nextRequest);
    }

    public static void startService(Context context, ActionRequest request) {
        switch(request.getAction()) {
            case SYNC_QUEUE:
            case UPDATE_ARTICLES:
            case SWEEP_DELETED_ARTICLES:
                startService(context, request, true);
                break;

            case FETCH_IMAGES:
            case DOWNLOAD_AS_FILE:
                startService(context, request, false);
                break;

            default:
                throw new IllegalStateException("Action is not implemented: " + request.getAction());
        }
    }

    private static void startService(Context context, ActionRequest request, boolean mainService) {
        Intent intent = new Intent(context, mainService ? MainService.class : SecondaryService.class);
        intent.putExtra(ActionRequest.ACTION_REQUEST, request);

        context.startService(intent);
    }

    public static void enqueueSimpleServiceTask(Context context, ActionRequest request) {
        Intent intent = TaskService.newSimpleTaskIntent(context);
        intent.putExtra(ActionRequest.ACTION_REQUEST, request);

        context.startService(intent);
    }

    public static void enqueueServiceTask(Context context, TaskService.Task task,
                                          Runnable postCallCallback) {
        performBoundServiceCall(context, binder -> {
            TaskService.TaskServiceBinder service = (TaskService.TaskServiceBinder) binder;
            service.enqueueTask(task);
        }, postCallCallback);
    }

    public static void performBoundServiceCall(Context context, Consumer<IBinder> action,
                                               Runnable postCallCallback) {
        Log.d(TAG, "performBoundServiceCall() started");

        ServiceConnection serviceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.d(TAG, "onServiceConnected() name=" + name);

                try {
                    Log.v(TAG, "onServiceConnected() executing action");
                    action.accept(service);
                    Log.v(TAG, "onServiceConnected() finished executing action");
                } catch (Exception e) {
                    Log.w(TAG, "onServiceConnected() exception", e);
                    throw e; // ignore?
                } finally {
                    Log.v(TAG, "onServiceConnected() unbinding service");
                    context.unbindService(this);
                    Log.v(TAG, "onServiceConnected() posting postCallCallback");
                    if (postCallCallback != null) {
                        new Handler(context.getMainLooper()).post(postCallCallback);
                    }
                }
                Log.v(TAG, "onServiceConnected() finished");
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.d(TAG, "onServiceDisconnected() name=" + name);
            }
        };

        Log.d(TAG, "performBoundServiceCall() binding service");
        Intent serviceIntent = new Intent(context, TaskService.class);
        context.bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);

        Log.d(TAG, "performBoundServiceCall() finished");
    }

}
