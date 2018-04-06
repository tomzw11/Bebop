package com.parrot.sdksample.drone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.util.Log;

import com.parrot.arsdk.arcommands.ARCOMMANDS_ARDRONE3_MEDIARECORDEVENT_PICTUREEVENTCHANGED_ERROR_ENUM;
import com.parrot.arsdk.arcommands.ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_ENUM;
import com.parrot.arsdk.arcommands.*;
import com.parrot.arsdk.arcontroller.ARCONTROLLER_DEVICE_STATE_ENUM;
import com.parrot.arsdk.arcontroller.ARCONTROLLER_DICTIONARY_KEY_ENUM;
import com.parrot.arsdk.arcontroller.ARCONTROLLER_ERROR_ENUM;
import com.parrot.arsdk.arcontroller.ARControllerArgumentDictionary;
import com.parrot.arsdk.arcontroller.ARControllerDictionary;
import com.parrot.arsdk.arcontroller.ARControllerException;
import com.parrot.arsdk.arcontroller.ARDeviceController;
import com.parrot.arsdk.arcontroller.ARDeviceControllerListener;
import com.parrot.arsdk.arcontroller.ARFeatureARDrone3;
import com.parrot.arsdk.arcontroller.ARFeatureCommon;
import com.parrot.arsdk.ardiscovery.ARDISCOVERY_PRODUCT_ENUM;
import com.parrot.arsdk.ardiscovery.ARDISCOVERY_PRODUCT_FAMILY_ENUM;
import com.parrot.arsdk.ardiscovery.ARDiscoveryDevice;
import com.parrot.arsdk.ardiscovery.ARDiscoveryDeviceService;
import com.parrot.arsdk.ardiscovery.ARDiscoveryException;
import com.parrot.arsdk.ardiscovery.ARDiscoveryService;
import com.parrot.arsdk.arutils.ARUTILS_DESTINATION_ENUM;
import com.parrot.arsdk.arutils.ARUTILS_FTP_TYPE_ENUM;
import com.parrot.arsdk.arutils.ARUtilsException;
import com.parrot.arsdk.arutils.ARUtilsManager;
import com.parrot.arsdk.arcontroller.ARControllerCodec;
import com.parrot.arsdk.arcontroller.ARFrame;
import com.parrot.arsdk.arcontroller.ARDeviceControllerStreamListener;

import com.parrot.sdksample.Tensorflow.Classifier;
import com.parrot.sdksample.Tensorflow.beliefUpdateHandler;
import com.parrot.sdksample.Tensorflow.initialStateHandler;
import com.parrot.sdksample.Tensorflow.initialStateHandler.initOutput;
import com.parrot.sdksample.Tensorflow.nextStateHandler;
import com.parrot.sdksample.Tensorflow.nextStateHandler.nextOutput;
import com.parrot.sdksample.Tensorflow.beliefUpdateHandler.beliefUpdateOutput;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BebopDrone {
    private static final String TAG = "BebopDrone";
    private static String storage_location = "/storage/emulated/0/ARSDKMedias/";
    private Bitmap pictureBitmap;
    private List<Bitmap> pictureBitmapList = new ArrayList<Bitmap>();
    private static final int INPUT_SIZE = 224;
    private static final int IMAGE_MEAN = 128;
    private static final float IMAGE_STD = 128;

    public interface Listener {
        /**
         * Called when the connection to the drone changes
         * Called in the main thread
         * @param state the state of the drone
         */
        void onDroneConnectionChanged(ARCONTROLLER_DEVICE_STATE_ENUM state);

        /**
         * Called when the battery charge changes
         * Called in the main thread
         * @param batteryPercentage the battery remaining (in percent)
         */
        void onBatteryChargeChanged(int batteryPercentage);

        /**
         * Called when the piloting state changes
         * Called in the main thread
         * @param state the piloting state of the drone
         */
        void onPilotingStateChanged(ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_ENUM state);

        /**
         * Called when a picture is taken
         * Called on a separate thread
         * @param error ERROR_OK if picture has been taken, otherwise describe the error
         */
        void onPictureTaken(ARCOMMANDS_ARDRONE3_MEDIARECORDEVENT_PICTUREEVENTCHANGED_ERROR_ENUM error);

        /**
         * Called before medias will be downloaded
         * Called in the main thread
         * @param nbMedias the number of medias that will be downloaded
         */
        void onMatchingMediasFound(int nbMedias);

        /**
         * Called each time the progress of a download changes
         * Called in the main thread
         * @param mediaName the name of the media
         * @param progress the progress of its download (from 0 to 100)
         */
        void onDownloadProgressed(String mediaName, int progress);

        /**
         * Called when a media download has ended
         * Called in the main thread
         * @param mediaName the name of the media
         */
        void onDownloadComplete(String mediaName);

        void onSpeedChanged(float x,float y,float z);

        void onMovebyEnd(ARCOMMANDS_ARDRONE3_PILOTINGEVENT_MOVEBYEND_ERROR_ENUM moveByEnd);

        void onPositionChanged(double X, double Y, double Z);

        /**
         * Called when the video decoder should be configured
         * Called on a separate thread
         * @param codec the codec to configure the decoder with
         */
        void configureDecoder(ARControllerCodec codec);

        /**
         * Called when a video frame has been received
         * Called on a separate thread
         * @param frame the video frame
         */
        void onFrameReceived(ARFrame frame);
    }

    private final List<Listener> mListeners;

    private final Handler mHandler;
    private final Context mContext;

    //        current states and beliefs in POMDP that updates with each move.
    private int[] mstate;
    private int[] mpos;
    private int[] mstop;
    private int[] moccupancy;
    private int[] mobs_level;
    private float[] mbelief;
    private float[] mnextcoords;

    private nextOutput mnextOutput;
    private beliefUpdateOutput mbeliefOutput;

    private float zero = 0f;
    //        add offset since the drone is 1m above ground after takeoff.
    private float[] current_coords = {zero,0.27f,1f};

    private ARDeviceController mDeviceController;
    private SDCardModule mSDCardModule;
    private ARCONTROLLER_DEVICE_STATE_ENUM mState;
    private ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_ENUM mFlyingState;
    private String mCurrentRunId;
    private ARDiscoveryDeviceService mDeviceService;
    private ARUtilsManager mFtpListManager;
    private ARUtilsManager mFtpQueueManager;

    public BebopDrone(Context context, @NonNull ARDiscoveryDeviceService deviceService) {

        mContext = context;
        mListeners = new ArrayList<>();
        mDeviceService = deviceService;

        // needed because some callbacks will be called on the main thread
        mHandler = new Handler(context.getMainLooper());

        mState = ARCONTROLLER_DEVICE_STATE_ENUM.ARCONTROLLER_DEVICE_STATE_STOPPED;

        // if the product type of the deviceService match with the types supported
        ARDISCOVERY_PRODUCT_ENUM productType = ARDiscoveryService.getProductFromProductID(mDeviceService.getProductID());
        ARDISCOVERY_PRODUCT_FAMILY_ENUM family = ARDiscoveryService.getProductFamily(productType);
        if (ARDISCOVERY_PRODUCT_FAMILY_ENUM.ARDISCOVERY_PRODUCT_FAMILY_ARDRONE.equals(family)) {

            ARDiscoveryDevice discoveryDevice = createDiscoveryDevice(mDeviceService);
            if (discoveryDevice != null) {
                mDeviceController = createDeviceController(discoveryDevice);
                discoveryDevice.dispose();
            }

            try
            {
                mFtpListManager = new ARUtilsManager();
                mFtpQueueManager = new ARUtilsManager();

                mFtpListManager.initFtp(mContext, deviceService, ARUTILS_DESTINATION_ENUM.ARUTILS_DESTINATION_DRONE, ARUTILS_FTP_TYPE_ENUM.ARUTILS_FTP_TYPE_GENERIC);
                mFtpQueueManager.initFtp(mContext, deviceService, ARUTILS_DESTINATION_ENUM.ARUTILS_DESTINATION_DRONE, ARUTILS_FTP_TYPE_ENUM.ARUTILS_FTP_TYPE_GENERIC);

                mSDCardModule = new SDCardModule(mFtpListManager, mFtpQueueManager);
                mSDCardModule.addListener(mSDCardModuleListener);
            }
            catch (ARUtilsException e)
            {
                Log.e(TAG, "Exception", e);
            }

        } else {
            Log.e(TAG, "DeviceService type is not supported by BebopDrone");
        }
    }

    public void dispose()
    {
        if (mDeviceController != null)
            mDeviceController.dispose();
        if (mFtpListManager != null)
            mFtpListManager.closeFtp(mContext, mDeviceService);
        if (mFtpQueueManager != null)
            mFtpQueueManager.closeFtp(mContext, mDeviceService);
    }

    //region Listener functions
    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }
    //endregion Listener

    /**
     * Connect to the drone
     * @return true if operation was successful.
     *              Returning true doesn't mean that device is connected.
     *              You can be informed of the actual connection through {@link Listener#onDroneConnectionChanged}
     */
    public boolean connect() {
        boolean success = false;
        if ((mDeviceController != null) && (ARCONTROLLER_DEVICE_STATE_ENUM.ARCONTROLLER_DEVICE_STATE_STOPPED.equals(mState))) {
            ARCONTROLLER_ERROR_ENUM error = mDeviceController.start();
            if (error == ARCONTROLLER_ERROR_ENUM.ARCONTROLLER_OK) {
                success = true;
            }
            mDeviceController.getFeatureARDrone3().sendCameraOrientationV2(-100, 0);
        }
        return success;
    }

    /**
     * Disconnect from the drone
     * @return true if operation was successful.
     *              Returning true doesn't mean that device is disconnected.
     *              You can be informed of the actual disconnection through {@link Listener#onDroneConnectionChanged}
     */
    public boolean disconnect() {
        boolean success = false;
        if ((mDeviceController != null) && (ARCONTROLLER_DEVICE_STATE_ENUM.ARCONTROLLER_DEVICE_STATE_RUNNING.equals(mState))) {
            ARCONTROLLER_ERROR_ENUM error = mDeviceController.stop();
            if (error == ARCONTROLLER_ERROR_ENUM.ARCONTROLLER_OK) {
                success = true;
            }
        }
        return success;
    }

    /**
     * Get the current connection state
     * @return the connection state of the drone
     */
    public ARCONTROLLER_DEVICE_STATE_ENUM getConnectionState() {
        return mState;
    }

    /**
     * Get the current flying state
     * @return the flying state
     */
    public ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_ENUM getFlyingState() {
        return mFlyingState;
    }

    public void takeOff() {
        if ((mDeviceController != null) && (mState.equals(ARCONTROLLER_DEVICE_STATE_ENUM.ARCONTROLLER_DEVICE_STATE_RUNNING))) {

            mDeviceController.getFeatureARDrone3().sendPilotingTakeOff();
            mDeviceController.getFeatureARDrone3().sendCameraOrientationV2(-100, 0);
        }
    }

    public void land() {
        if ((mDeviceController != null) && (mState.equals(ARCONTROLLER_DEVICE_STATE_ENUM.ARCONTROLLER_DEVICE_STATE_RUNNING))) {
            mDeviceController.getFeatureARDrone3().sendPilotingLanding();
        }
    }

    public void emergency() {
        if ((mDeviceController != null) && (mState.equals(ARCONTROLLER_DEVICE_STATE_ENUM.ARCONTROLLER_DEVICE_STATE_RUNNING))) {
            mDeviceController.getFeatureARDrone3().sendPilotingEmergency();
        }
    }

    public void takePicture() {
        if ((mDeviceController != null) && (mState.equals(ARCONTROLLER_DEVICE_STATE_ENUM.ARCONTROLLER_DEVICE_STATE_RUNNING))) {

            mDeviceController.getFeatureARDrone3().sendMediaRecordPictureV2();
        }
    }

    public void lookDown(){
        if(mDeviceController != null){
            mDeviceController.getFeatureARDrone3().sendCameraOrientationV2(-100, 0);
        }
    }

    public int[] runAZnet(Classifier classifier,String mediaName){

        String file_path = storage_location + mediaName;

        pictureBitmap = BitmapFactory.decodeFile(file_path);
        pictureBitmap = Bitmap.createScaledBitmap(pictureBitmap, INPUT_SIZE, INPUT_SIZE, false);

        List<Bitmap> temp = new ArrayList<Bitmap>();

        temp.add(0,
                Bitmap.createBitmap(pictureBitmap, pictureBitmap.getWidth()/2, pictureBitmap.getHeight()/2, pictureBitmap.getWidth()/2, pictureBitmap.getHeight()/2));
        temp.add(1,
                Bitmap.createBitmap(pictureBitmap, 0, pictureBitmap.getHeight()/2, pictureBitmap.getWidth()/2, pictureBitmap.getHeight()/2));
        temp.add(2,
                Bitmap.createBitmap(pictureBitmap, pictureBitmap.getWidth()/2, 0, pictureBitmap.getWidth()/2, pictureBitmap.getHeight()/2));
        temp.add(3,
                Bitmap.createBitmap(pictureBitmap, 0, 0, pictureBitmap.getWidth()/2, pictureBitmap.getHeight()/2));
        for (int i = 0; i < 4; i++) {
            pictureBitmapList.add(i, Bitmap.createScaledBitmap(temp.get(i), INPUT_SIZE, INPUT_SIZE, false));
        }
        pictureBitmap = Bitmap.createScaledBitmap(pictureBitmap, INPUT_SIZE, INPUT_SIZE, false);

        long results = 1;
        for (int i = 0; i < 4; i ++) {
            long temp_score = classifier.recognizeImage(pictureBitmapList.get(i));
            results = results + temp_score*(long)Math.pow(2, (double) i);
        }

        int[] obs = new int[1];
        obs[0] = (int)results;
        return obs;
    }

    private float[] getNavigation(float[] next_coords){

//        Coordinate systems of the drone in meters.
//                The coordinates are always represented as (latitude, longitude, height).
//                Our convention:

//        (1) The head of the drone faces the direction of increasing latitude (north 90 degrees and south 0 degrees).
//        (2) The left side of the drone faces the direction of increasing longitude (left + and right -).
//        (2) In the angles describing the FOV, the first entry is in the direction of increasing latitude/longitude.

        float[] nav = new float[3];
        nav[0] = -heightOffset(current_coords[2],next_coords[2])+(next_coords[0]-current_coords[0]);
        nav[1] = -(next_coords[1]-current_coords[1]);
        nav[2] = -(next_coords[2]-current_coords[2]);

        current_coords[0] = next_coords[0];
        current_coords[1] = next_coords[1];
        current_coords[2] = next_coords[2];

        return nav;
    }

    public void updateInitialState(initOutput initOutput){

        mstate = initOutput.getState();
        mbelief = initOutput.getBelief();
        moccupancy = initOutput.getOccupancy();
    }

    public boolean idleSensing(){

        if(mobs_level[0]==0){
            return true;
        }else{
            return false;
        }
    }

    public void updateBelief(beliefUpdateHandler beliefUpdateHandler, int[] obs){

        mbeliefOutput = beliefUpdateHandler.getState(
                mpos,
                obs,
                mobs_level,
                mbelief,
                moccupancy
                );

        mbelief = mbeliefOutput.getNextBelief();
        moccupancy = mbeliefOutput.getNextOccupancy();

//        Log.d(TAG,"mbelief: " + Arrays.toString(mbelief));
    }

    public nextOutput updateNextState(nextStateHandler nextStateHandler){

        mnextOutput = nextStateHandler.getState(mstate,mbelief,moccupancy);

        mstate = mnextOutput.getNextState();
        mstop = mnextOutput.getStop();
        mobs_level = mnextOutput.getObs_level();
        mpos = mnextOutput.getNext_pos();
        mnextcoords = mnextOutput.getNext_coords();

        return mnextOutput;
    }

    public void testDirection(){

        mDeviceController.getFeatureARDrone3().sendPilotingMoveBy(1f,1f,0,0);

    }

    public float heightOffset(float current_height, float next_height){

        double current_offset = (double)current_height * (Math.tan(17./180*Math.PI)+Math.tan(40./180*Math.PI)) / 2. - (double)current_height * (Math.tan(17./180*Math.PI));
        double new_offset = (double)next_height * (Math.tan(17./180*Math.PI)+Math.tan(40./180*Math.PI)) / 2. - (double)next_height * (Math.tan(17./180*Math.PI));

        return (float)(new_offset-current_offset);
    }

    public float[] runSearch(float[] next_coords){

        double distance = Math.sqrt(current_coords[0]*current_coords[0]+
                current_coords[1]*current_coords[1]+
                current_coords[2]*current_coords[2]);

        float[] nav_command = getNavigation(next_coords);

        if ((mDeviceController != null) && distance < 38
                &&(mState.equals(ARCONTROLLER_DEVICE_STATE_ENUM.ARCONTROLLER_DEVICE_STATE_RUNNING))) {
            try{

                mDeviceController.getFeatureARDrone3().sendPilotingMoveBy(nav_command[0],nav_command[1],nav_command[2],0);

            }catch(Exception e){

                mDeviceController.getFeatureARDrone3().sendPilotingLanding();
            }
        }else{

            mDeviceController.getFeatureARDrone3().sendPilotingLanding();
        }

        //only for testing.
//        try{
//            Thread.sleep(1000);
//
//        }catch (InterruptedException e){
//            Log.d(TAG,"sleep interrupted.");
//        }

//        mDeviceController.getFeatureARDrone3().sendMediaRecordPictureV2();
//        Log.d(TAG,"pic taken in runsearch.");
        return next_coords;
    }

    /**
     * Download the last flight medias
     * Uses the run id to download all medias related to the last flight
     * If no run id is available, download all medias of the day
     */
    public void getLastFlightMedias() {
        String runId = mCurrentRunId;
        if ((runId != null) && !runId.isEmpty()) {
            mSDCardModule.getFlightMedias(runId);
        }
//        else {
//            Log.e(TAG, "RunID not available, fallback to the day's medias");
//            mSDCardModule.getTodaysFlightMedias();
//        }
    }

    public void getLastPicture(){

//        Log.d(TAG,"getLastPicture");
        mSDCardModule.downloadLastMedium();
    }

    private ARDiscoveryDevice createDiscoveryDevice(@NonNull ARDiscoveryDeviceService service) {
        ARDiscoveryDevice device = null;
        try {
            device = new ARDiscoveryDevice(mContext, service);
        } catch (ARDiscoveryException e) {
            Log.e(TAG, "Exception", e);
            Log.e(TAG, "Error: " + e.getError());
        }

        return device;
    }

    private ARDeviceController createDeviceController(@NonNull ARDiscoveryDevice discoveryDevice) {
        ARDeviceController deviceController = null;
        try {
            deviceController = new ARDeviceController(discoveryDevice);

            deviceController.addListener(mDeviceControllerListener);
            deviceController.addStreamListener(mStreamListener);

        } catch (ARControllerException e) {
            Log.e(TAG, "Exception", e);
        }

        return deviceController;
    }

    //region notify listener block
    private void notifyConnectionChanged(ARCONTROLLER_DEVICE_STATE_ENUM state) {
        List<Listener> listenersCpy = new ArrayList<>(mListeners);
        for (Listener listener : listenersCpy) {
            listener.onDroneConnectionChanged(state);
        }
    }

    private void notifyBatteryChanged(int battery) {
        List<Listener> listenersCpy = new ArrayList<>(mListeners);
        for (Listener listener : listenersCpy) {
            listener.onBatteryChargeChanged(battery);
        }
    }

    private void notifySpeedChanged(float current_speed_X,float current_speed_Y,float current_speed_Z) {
        List<Listener> listenersCpy = new ArrayList<>(mListeners);
        for (Listener listener : listenersCpy) {
            listener.onSpeedChanged(current_speed_X,current_speed_Y,current_speed_Z);
        }
    }

    private void notifyMovebyEnd(ARCOMMANDS_ARDRONE3_PILOTINGEVENT_MOVEBYEND_ERROR_ENUM moveByEnd) {
        List<Listener> listenersCpy = new ArrayList<>(mListeners);
        for (Listener listener : listenersCpy) {
            listener.onMovebyEnd(moveByEnd);
        }
    }

    private void notifyPositionChanged(double X, double Y, double Z){
        List<Listener> listenersCpy = new ArrayList<>(mListeners);
        for (Listener listener : listenersCpy) {
            listener.onPositionChanged(X,Y,Z);
        }
    }

    private void notifyPilotingStateChanged(ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_ENUM state) {
        List<Listener> listenersCpy = new ArrayList<>(mListeners);
        for (Listener listener : listenersCpy) {
            listener.onPilotingStateChanged(state);
        }
    }

    private void notifyPictureTaken(ARCOMMANDS_ARDRONE3_MEDIARECORDEVENT_PICTUREEVENTCHANGED_ERROR_ENUM error) {
        List<Listener> listenersCpy = new ArrayList<>(mListeners);
        for (Listener listener : listenersCpy) {
            listener.onPictureTaken(error);
        }
    }

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

    private void notifyConfigureDecoder(ARControllerCodec codec) {
        List<Listener> listenersCpy = new ArrayList<>(mListeners);
        for (Listener listener : listenersCpy) {
            listener.configureDecoder(codec);
        }
    }

    private void notifyFrameReceived(ARFrame frame) {
        List<Listener> listenersCpy = new ArrayList<>(mListeners);
        for (Listener listener : listenersCpy) {
            listener.onFrameReceived(frame);
        }
    }

    //endregion notify listener block

    private final SDCardModule.Listener mSDCardModuleListener = new SDCardModule.Listener() {

        @Override
        public void onMatchingMediasFound(final int nbMedias) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    notifyMatchingMediasFound(nbMedias);
                }
            });
        }

        @Override
        public void onDownloadProgressed(final String mediaName, final int progress) {
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    notifyDownloadProgressed(mediaName, progress);
                }
            });
        }

        @Override
        public void onDownloadComplete(final String mediaName) {

            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    notifyDownloadComplete(mediaName);
                }
            });

        }
    };

    private final ARDeviceControllerListener mDeviceControllerListener = new ARDeviceControllerListener() {
        @Override
        public void onStateChanged(ARDeviceController deviceController, ARCONTROLLER_DEVICE_STATE_ENUM newState, ARCONTROLLER_ERROR_ENUM error) {
            mState = newState;
            if (ARCONTROLLER_DEVICE_STATE_ENUM.ARCONTROLLER_DEVICE_STATE_RUNNING.equals(mState)) {
                mDeviceController.getFeatureARDrone3().sendMediaStreamingVideoEnable((byte) 1);
            }
//            else if (ARCONTROLLER_DEVICE_STATE_ENUM.ARCONTROLLER_DEVICE_STATE_STOPPED.equals(mState)) {
//                mSDCardModule.cancelGetFlightMedias();
//            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    notifyConnectionChanged(mState);
                }
            });
        }

        @Override
        public void onExtensionStateChanged(ARDeviceController deviceController, ARCONTROLLER_DEVICE_STATE_ENUM newState, ARDISCOVERY_PRODUCT_ENUM product, String name, ARCONTROLLER_ERROR_ENUM error) {
        }

        @Override
        public void onCommandReceived(ARDeviceController deviceController, ARCONTROLLER_DICTIONARY_KEY_ENUM commandKey, ARControllerDictionary elementDictionary) {
            // if event received is the battery update
            if ((commandKey == ARCONTROLLER_DICTIONARY_KEY_ENUM.ARCONTROLLER_DICTIONARY_KEY_COMMON_COMMONSTATE_BATTERYSTATECHANGED) && (elementDictionary != null)) {
                ARControllerArgumentDictionary<Object> args = elementDictionary.get(ARControllerDictionary.ARCONTROLLER_DICTIONARY_SINGLE_KEY);
                if (args != null) {
                    final int battery = (Integer) args.get(ARFeatureCommon.ARCONTROLLER_DICTIONARY_KEY_COMMON_COMMONSTATE_BATTERYSTATECHANGED_PERCENT);
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            notifyBatteryChanged(battery);
                        }
                    });
                }
            }
            // if event received is the flying state update
            else if ((commandKey == ARCONTROLLER_DICTIONARY_KEY_ENUM.ARCONTROLLER_DICTIONARY_KEY_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED) && (elementDictionary != null)) {
                ARControllerArgumentDictionary<Object> args = elementDictionary.get(ARControllerDictionary.ARCONTROLLER_DICTIONARY_SINGLE_KEY);
                if (args != null) {
                    final ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_ENUM state = ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_ENUM.getFromValue((Integer) args.get(ARFeatureARDrone3.ARCONTROLLER_DICTIONARY_KEY_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE));

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mFlyingState = state;
                            notifyPilotingStateChanged(state);
                        }
                    });
                }
            }
            // if event received is the picture notification
            else if ((commandKey == ARCONTROLLER_DICTIONARY_KEY_ENUM.ARCONTROLLER_DICTIONARY_KEY_ARDRONE3_MEDIARECORDEVENT_PICTUREEVENTCHANGED) && (elementDictionary != null)){
                ARControllerArgumentDictionary<Object> args = elementDictionary.get(ARControllerDictionary.ARCONTROLLER_DICTIONARY_SINGLE_KEY);
                if (args != null) {
                    final ARCOMMANDS_ARDRONE3_MEDIARECORDEVENT_PICTUREEVENTCHANGED_ERROR_ENUM error = ARCOMMANDS_ARDRONE3_MEDIARECORDEVENT_PICTUREEVENTCHANGED_ERROR_ENUM.getFromValue((Integer)args.get(ARFeatureARDrone3.ARCONTROLLER_DICTIONARY_KEY_ARDRONE3_MEDIARECORDEVENT_PICTUREEVENTCHANGED_ERROR));
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            notifyPictureTaken(error);
                        }
                    });
                }
            }
            // if event received is the run id
            else if ((commandKey == ARCONTROLLER_DICTIONARY_KEY_ENUM.ARCONTROLLER_DICTIONARY_KEY_COMMON_RUNSTATE_RUNIDCHANGED) && (elementDictionary != null)) {
                ARControllerArgumentDictionary<Object> args = elementDictionary.get(ARControllerDictionary.ARCONTROLLER_DICTIONARY_SINGLE_KEY);
                if (args != null) {
                    final String runID = (String) args.get(ARFeatureCommon.ARCONTROLLER_DICTIONARY_KEY_COMMON_RUNSTATE_RUNIDCHANGED_RUNID);
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mCurrentRunId = runID;
                        }
                    });
                }
            }
            // speed of drone.
            else if ((commandKey == ARCONTROLLER_DICTIONARY_KEY_ENUM.ARCONTROLLER_DICTIONARY_KEY_ARDRONE3_PILOTINGSTATE_SPEEDCHANGED) && (elementDictionary != null)){
                ARControllerArgumentDictionary<Object> args = elementDictionary.get(ARControllerDictionary.ARCONTROLLER_DICTIONARY_SINGLE_KEY);
                if (args != null) {

                    final float speedX = (float)((Double)args.get(ARFeatureARDrone3.ARCONTROLLER_DICTIONARY_KEY_ARDRONE3_PILOTINGSTATE_SPEEDCHANGED_SPEEDX)).doubleValue();
                    final float speedY = (float)((Double)args.get(ARFeatureARDrone3.ARCONTROLLER_DICTIONARY_KEY_ARDRONE3_PILOTINGSTATE_SPEEDCHANGED_SPEEDY)).doubleValue();
                    final float speedZ = (float)((Double)args.get(ARFeatureARDrone3.ARCONTROLLER_DICTIONARY_KEY_ARDRONE3_PILOTINGSTATE_SPEEDCHANGED_SPEEDZ)).doubleValue();

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                              notifySpeedChanged(speedX,speedY,speedZ);
                        }
                    });
                }
            }
            // position of drone.
            else if ((commandKey == ARCONTROLLER_DICTIONARY_KEY_ENUM.ARCONTROLLER_DICTIONARY_KEY_ARDRONE3_PILOTINGSTATE_POSITIONCHANGED) && (elementDictionary != null)){
                ARControllerArgumentDictionary<Object> args = elementDictionary.get(ARControllerDictionary.ARCONTROLLER_DICTIONARY_SINGLE_KEY);
                if (args != null) {

                    final double latitude = (double)args.get(ARFeatureARDrone3.ARCONTROLLER_DICTIONARY_KEY_ARDRONE3_PILOTINGSTATE_POSITIONCHANGED_LATITUDE);
                    final double longitude = (double)args.get(ARFeatureARDrone3.ARCONTROLLER_DICTIONARY_KEY_ARDRONE3_PILOTINGSTATE_POSITIONCHANGED_LONGITUDE);
                    final double altitude = (double)args.get(ARFeatureARDrone3.ARCONTROLLER_DICTIONARY_KEY_ARDRONE3_PILOTINGSTATE_POSITIONCHANGED_ALTITUDE);
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {

                            notifyPositionChanged(latitude,longitude,altitude);
                        }
                    });
                }
            }
            // displacement of drone after moveBy.
            else if ((commandKey == ARCONTROLLER_DICTIONARY_KEY_ENUM.ARCONTROLLER_DICTIONARY_KEY_ARDRONE3_PILOTINGEVENT_MOVEBYEND) && (elementDictionary != null)){
                ARControllerArgumentDictionary<Object> args = elementDictionary.get(ARControllerDictionary.ARCONTROLLER_DICTIONARY_SINGLE_KEY);
                if (args != null) {

                    final ARCOMMANDS_ARDRONE3_PILOTINGEVENT_MOVEBYEND_ERROR_ENUM error = ARCOMMANDS_ARDRONE3_PILOTINGEVENT_MOVEBYEND_ERROR_ENUM.getFromValue((Integer)args.get(ARFeatureARDrone3.ARCONTROLLER_DICTIONARY_KEY_ARDRONE3_PILOTINGEVENT_MOVEBYEND_ERROR));

                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {

                            notifyMovebyEnd(error);
                        }
                    });
                }
            }


        }
    };

    private final ARDeviceControllerStreamListener mStreamListener = new ARDeviceControllerStreamListener() {
        @Override
        public ARCONTROLLER_ERROR_ENUM configureDecoder(ARDeviceController deviceController, final ARControllerCodec codec) {
            notifyConfigureDecoder(codec);
            return ARCONTROLLER_ERROR_ENUM.ARCONTROLLER_OK;
        }

        @Override
        public ARCONTROLLER_ERROR_ENUM onFrameReceived(ARDeviceController deviceController, final ARFrame frame) {
            notifyFrameReceived(frame);
            return ARCONTROLLER_ERROR_ENUM.ARCONTROLLER_OK;
        }

        @Override
        public void onFrameTimeout(ARDeviceController deviceController) {}
    };

}
