package com.parrot.sdksample.drone;

import android.content.Context;
import android.nfc.Tag;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.util.Log;

import com.parrot.arsdk.ardatatransfer.ARDATATRANSFER_ERROR_ENUM;
import com.parrot.arsdk.ardatatransfer.ARDataTransferException;
import com.parrot.arsdk.ardatatransfer.ARDataTransferManager;
import com.parrot.arsdk.ardatatransfer.ARDataTransferMedia;
import com.parrot.arsdk.ardatatransfer.ARDataTransferMediasDownloader;
import com.parrot.arsdk.ardatatransfer.ARDataTransferMediasDownloaderCompletionListener;
import com.parrot.arsdk.ardatatransfer.ARDataTransferMediasDownloaderProgressListener;
import com.parrot.arsdk.arutils.ARUtilsManager;
import com.parrot.sdksample.activity.BebopActivity;
import com.parrot.sdksample.activity.Navigation;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Socket;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Locale;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class SDCardModule {

    private static final String TAG = "SDCardModule";

    private static final String DRONE_MEDIA_FOLDER = "internal_000";
    private static final String MOBILE_MEDIA_FOLDER = "/ARSDKMedias/";

    //ip address of lab server.
//    private static final String server_ip = "137.110.147.11";
    private static final String server_ip = "137.110.115.6";

    private static final int send_port = 8888;
    private static final int receive_port = 8887;

    public interface Listener {
        /**
         * Called before medias will be downloaded
         * Called on a separate thread
         * @param nbMedias the number of medias that will be downloaded
         */
        void onMatchingMediasFound(int nbMedias);

        /**
         * Called each time the progress of a download changes
         * Called on a separate thread
         * @param mediaName the name of the media
         * @param progress the progress of its download (from 0 to 100)
         */
        void onDownloadProgressed(String mediaName, int progress);

        /**
         * Called when a media download has ended
         * Called on a separate thread
         * @param mediaName the name of the media
         */
        void onDownloadComplete(String mediaName);
    }

    private final List<Listener> mListeners;

    private ARDataTransferManager mDataTransferManager;
    private ARUtilsManager mFtpList;
    private ARUtilsManager mFtpQueue;

    private boolean mThreadIsRunning;
    private boolean mIsCancelled;

    private int mNbMediasToDownload;
    private int mCurrentDownloadIndex;

    public SDCardModule(@NonNull ARUtilsManager ftpListManager, @NonNull ARUtilsManager ftpQueueManager) {

        mThreadIsRunning = false;
        mListeners = new ArrayList<>();

        mFtpList = ftpListManager;
        mFtpQueue = ftpQueueManager;

        ARDATATRANSFER_ERROR_ENUM result = ARDATATRANSFER_ERROR_ENUM.ARDATATRANSFER_OK;
        try {
            mDataTransferManager = new ARDataTransferManager();
        } catch (ARDataTransferException e) {
//            Log.e(TAG, "Exception", e);
            result = ARDATATRANSFER_ERROR_ENUM.ARDATATRANSFER_ERROR;
        }

        if (result == ARDATATRANSFER_ERROR_ENUM.ARDATATRANSFER_OK) {
            // direct to external directory
            String externalDirectory = Environment.getExternalStorageDirectory().toString().concat(MOBILE_MEDIA_FOLDER);

            // if the directory doesn't exist, create it
            File f = new File(externalDirectory);
            if(!(f.exists() && f.isDirectory())) {
                boolean success = f.mkdir();
                if (!success) {
//                    Log.e(TAG, "Failed to create the folder " + externalDirectory);
                }
            }
            try {
                mDataTransferManager.getARDataTransferMediasDownloader().createMediasDownloader(mFtpList, mFtpQueue, DRONE_MEDIA_FOLDER, externalDirectory);
            } catch (ARDataTransferException e) {
//                Log.e(TAG, "Exception", e);
                result = e.getError();
            }
        }

        if (result != ARDATATRANSFER_ERROR_ENUM.ARDATATRANSFER_OK) {
            // clean up here because an error happened
            mDataTransferManager.dispose();
            mDataTransferManager = null;
        }
    }

    //region Listener functions
    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }
    //endregion Listener

    public void getFlightMedias(final String runId) {
        if (!mThreadIsRunning) {
            mThreadIsRunning = true;
            new Thread(new Runnable() {
                @Override
                public void run() {

                    ArrayList<ARDataTransferMedia> mediaList = getMediaList();

                    ArrayList<ARDataTransferMedia> mediasFromRun = null;
                    mNbMediasToDownload = 0;

                    Log.d(TAG,String.valueOf(mIsCancelled));
                    Log.d(TAG,String.valueOf(mediaList.isEmpty()));

                    if ((mediaList != null) && !mIsCancelled) {
                        mediasFromRun = getRunIdMatchingMedias(mediaList, runId);
                        mNbMediasToDownload = mediasFromRun.size();
                    }

                    notifyMatchingMediasFound(mNbMediasToDownload);

                    if ((mediasFromRun != null) && (mNbMediasToDownload != 0) && !mIsCancelled) {
                        downloadMedias(mediasFromRun);
                    }

                    mThreadIsRunning = false;
                    mIsCancelled = false;
                }
            }).start();
        }
    }

    public void downloadLastMedium() {

//        Log.d(TAG,"download LastMedium start");
        new Thread(new Runnable() {
                @Override
                public void run() {

                    ArrayList<ARDataTransferMedia> mediaList = getMediaList();

                    ArrayList<ARDataTransferMedia> mediaFromDate = null;
                    mNbMediasToDownload = 0;
                    if ((mediaList != null) && !mIsCancelled) {
                        GregorianCalendar today = new GregorianCalendar();
                        mediaFromDate = getLatestMedium(mediaList, today);
                        mNbMediasToDownload = 1;
//                        Log.i(TAG,"numberofdemia to download?");

                    }

//                    notifyMatchingMediasFound(mNbMediasToDownload);

                    if ((mediaFromDate != null) && (mNbMediasToDownload != 0) && !mIsCancelled) {
                        downloadMedias(mediaFromDate);
//                        Log.i(TAG,"bypass?");

                    }

                    String fileName = mediaFromDate.get(0).getName();
//                    Log.i(TAG,"download lastmedium end "+ fileName);

                }
            }).start();
    }

    private ArrayList<ARDataTransferMedia> getMediaList() {
        ArrayList<ARDataTransferMedia> mediaList = null;

        ARDataTransferMediasDownloader mediasDownloader = null;
        if (mDataTransferManager != null)
        {
            mediasDownloader = mDataTransferManager.getARDataTransferMediasDownloader();
        }

        if (mediasDownloader != null)
        {
            try
            {
                int mediaListCount = mediasDownloader.getAvailableMediasSync(false);
                mediaList = new ArrayList<>(mediaListCount);
                for (int i = 0; ((i < mediaListCount) && !mIsCancelled) ; i++)
                {
                    ARDataTransferMedia currentMedia = mediasDownloader.getAvailableMediaAtIndex(i);
                    String fileName = currentMedia.getName();
                    if(fileName.contains(".jpg")){
                        mediaList.add(currentMedia);
                    }
                }
            }
            catch (ARDataTransferException e)
            {
                Log.e(TAG, "Exception", e);
                mediaList = null;
            }
        }
        return mediaList;
    }

    private @NonNull ArrayList<ARDataTransferMedia> getRunIdMatchingMedias(
            ArrayList<ARDataTransferMedia> mediaList,
            String runId) {
        ArrayList<ARDataTransferMedia> matchingMedias = new ArrayList<>();
        for (ARDataTransferMedia media : mediaList) {
            if (media.getName().contains(runId)) {
                matchingMedias.add(media);
            }

            // exit if the async task is cancelled
            if (mIsCancelled) {
                break;
            }
        }

        return matchingMedias;
    }

    private ArrayList<ARDataTransferMedia> getDateMatchingMedias(ArrayList<ARDataTransferMedia> mediaList,
                                                                 GregorianCalendar matchingCal) {
        ArrayList<ARDataTransferMedia> matchingMedias = new ArrayList<>();
        Calendar mediaCal = new GregorianCalendar();
        SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HHmmss", Locale.getDefault());
        for (ARDataTransferMedia media : mediaList) {
            // convert date in string to calendar
            String dateStr = media.getDate();
            try {
                Date mediaDate = dateFormatter.parse(dateStr);
                mediaCal.setTime(mediaDate);

                // if the date are the same day
                if ((mediaCal.get(Calendar.DAY_OF_MONTH) == (matchingCal.get(Calendar.DAY_OF_MONTH))) &&
                        (mediaCal.get(Calendar.MONTH) == (matchingCal.get(Calendar.MONTH))) &&
                        (mediaCal.get(Calendar.YEAR) == (matchingCal.get(Calendar.YEAR)))) {
                    matchingMedias.add(media);
                }
            } catch (ParseException e) {
                Log.e(TAG, "Exception", e);
            }

            // exit if the async task is cancelled
            if (mIsCancelled) {
                break;
            }
        }

        return matchingMedias;
    }

    private ArrayList<ARDataTransferMedia> getLatestMedium(ArrayList<ARDataTransferMedia> mediaList,
                                                                 GregorianCalendar matchingCal) {

        ArrayList<ARDataTransferMedia> resMedia = new ArrayList<>();
        ARDataTransferMedia latestMedia = mediaList.get(0);
        Calendar mediaCal = new GregorianCalendar();
        SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HHmmss", Locale.getDefault());
        for (ARDataTransferMedia media : mediaList) {
            // convert date in string to calendar
            String dateStr = media.getDate();
//            Log.i(TAG,"getlastmedium media date" + dateStr);
            try {
                String latestDateStr = latestMedia.getDate();
                Date latestMediaDate = dateFormatter.parse(latestDateStr);
                Date mediaDate = dateFormatter.parse(dateStr);
                mediaCal.setTime(mediaDate);

                // if the medium is the latest.
                if (mediaDate.compareTo(latestMediaDate) == 1 &&
                        (mediaCal.get(Calendar.DAY_OF_MONTH) == (matchingCal.get(Calendar.DAY_OF_MONTH))) &&
                        (mediaCal.get(Calendar.MONTH) == (matchingCal.get(Calendar.MONTH))) &&
                        (mediaCal.get(Calendar.YEAR) == (matchingCal.get(Calendar.YEAR)))) {

                    latestMedia = media;
                }
            } catch (ParseException e) {
//                Log.e(TAG, "Exception", e);
            }

            // exit if the async task is cancelled
            if (mIsCancelled) {
                break;
            }
        }
        resMedia.add(latestMedia);
        return resMedia;
    }

    private void downloadMedias(@NonNull ArrayList<ARDataTransferMedia> matchingMedias) {
        mCurrentDownloadIndex = 1;

//        Log.d(TAG,"downloadMedias() from download lastmedium()");

        ARDataTransferMediasDownloader mediasDownloader = null;
        if (mDataTransferManager != null)
        {
            mediasDownloader = mDataTransferManager.getARDataTransferMediasDownloader();
        }

        if (mediasDownloader != null)
        {
            for (ARDataTransferMedia media : matchingMedias) {
                try {
                    mediasDownloader.addMediaToQueue(media, null, null, mDLCompletionListener, null);
                } catch (ARDataTransferException e) {
//                    Log.e(TAG, "Exception", e);
                }

                // exit if the async task is cancelled
                if (mIsCancelled) {
                    break;
                }
            }

            if (!mIsCancelled) {
//                Log.d(TAG,"downlaodMedias() downloader created");
                Runnable a = mediasDownloader.getDownloaderQueueRunnable();
//                Log.d(TAG,"downlaodMedias() runnable created");
                a.run();
//                Log.d(TAG,"downlaodMedias() run");

            }
        }
    }

    //region notify listener block
    private void notifyMatchingMediasFound(int nbMedias) {
        List<Listener> listenersCpy = new ArrayList<>(mListeners);
        for (Listener listener : listenersCpy) {
            listener.onMatchingMediasFound(nbMedias);
        }
    }

    private void notifyDownloadProgressed(String mediaName, int progress) {
        List<Listener> listenersCpy = new ArrayList<>(mListeners);
        for (Listener listener : listenersCpy) {
            listener.onDownloadProgressed(mediaName, progress);
        }
    }

    private void notifyDownloadComplete(String mediaName) {
        List<Listener> listenersCpy = new ArrayList<>(mListeners);
        for (Listener listener : listenersCpy) {
            listener.onDownloadComplete(mediaName);

        }
    }
    //endregion notify listener block

    private final ARDataTransferMediasDownloaderProgressListener mDLProgressListener = new ARDataTransferMediasDownloaderProgressListener() {
        private int mLastProgressSent = -1;
        @Override
        public void didMediaProgress(Object arg, ARDataTransferMedia media, float percent) {
            final int progressInt = (int) Math.floor(percent);
            if (mLastProgressSent != progressInt) {
                mLastProgressSent = progressInt;
                notifyDownloadProgressed(media.getName(), progressInt);
            }
        }
    };

    private final ARDataTransferMediasDownloaderCompletionListener mDLCompletionListener = new ARDataTransferMediasDownloaderCompletionListener() {
        @Override
        public void didMediaComplete(Object arg, ARDataTransferMedia media, ARDATATRANSFER_ERROR_ENUM error) {

            final String mediaName = media.getName();
            notifyDownloadComplete(mediaName);
//            Log.i(TAG,"notify complete callback "+ mediaName);

            // when all download are finished, stop the download runnable
            // in order to get out of the downloadMedias function
            mCurrentDownloadIndex++;
            if (mCurrentDownloadIndex > mNbMediasToDownload ) {
                ARDataTransferMediasDownloader mediasDownloader = null;
                if (mDataTransferManager != null) {
                    mediasDownloader = mDataTransferManager.getARDataTransferMediasDownloader();
                }

                if (mediasDownloader != null) {
                    mediasDownloader.cancelQueueThread();
                }
            }
//            TODO:could also call tf model here.

        }
    };
}
