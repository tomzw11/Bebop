package com.parrot.sdksample.activity;

import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.VideoView;
import android.media.Image;

import com.parrot.arsdk.arcommands.ARCOMMANDS_ARDRONE3_MEDIARECORDEVENT_PICTUREEVENTCHANGED_ERROR_ENUM;
import com.parrot.arsdk.arcommands.ARCOMMANDS_ARDRONE3_PILOTINGEVENT_MOVEBYEND_ERROR_ENUM;
import com.parrot.arsdk.arcommands.ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_ENUM;
import com.parrot.arsdk.arcontroller.ARCONTROLLER_DEVICE_STATE_ENUM;
import com.parrot.arsdk.ardiscovery.ARDiscoveryDeviceService;
import com.parrot.sdksample.R;
import com.parrot.sdksample.drone.BebopDrone;
import com.parrot.arsdk.arcontroller.ARControllerCodec;
import com.parrot.arsdk.arcontroller.ARFrame;
import com.parrot.sdksample.view.BebopVideoView;
import java.nio.ByteBuffer;


import com.parrot.sdksample.Tensorflow.Classifier;
import com.parrot.sdksample.Tensorflow.TensorFlowImageClassifier;
import com.parrot.sdksample.Tensorflow.initialStateHandler;
import com.parrot.sdksample.Tensorflow.nextStateHandler;
import com.parrot.sdksample.Tensorflow.beliefUpdateHandler;
import com.parrot.sdksample.Tensorflow.initialStateHandler.initOutput;
import com.parrot.sdksample.Tensorflow.nextStateHandler.nextOutput;
import com.parrot.sdksample.Tensorflow.beliefUpdateHandler.beliefUpdateOutput;

import java.util.Arrays;

public class BebopActivity extends AppCompatActivity{

    private static final String TAG = "BebopActivity";
    private BebopDrone mBebopDrone;

    private ProgressDialog mConnectionProgressDialog;
    private static String storage_location = "/storage/emulated/0/ARSDKMedias/";

    private TextView mBatteryLabel;
    private TextView mFlyingStatus;
    private TextView mCurrentCoords;
    private TextView mSpeedX,mSpeedY,mSpeedZ;
    private TextView mStop;
    private ImageView imageView;
    private TextView mCNNstatus;
    private Button mTakeOffLandBt;
    private Button mEmergencyBt;
    private BebopVideoView mVideoView;

    private boolean test_mode = false;
    private boolean picture_mode = false;
    private boolean tree_search_mode = false;

    private Classifier classifier;
    private Bitmap pictureBitmap;
    private initialStateHandler initialStateHandler;
    private nextStateHandler nextStateHandler;
    private beliefUpdateHandler beliefUpdateHandler;

    private boolean screenshot = false;

    private static final int INPUT_SIZE = 224;
    private static final int IMAGE_MEAN = 128;
    private static final float IMAGE_STD = 128;
    private static final String INPUT_NAME = "input";
    private static final String OUTPUT_NAME = "output";

    private static final String MODEL_FILE = "file:///android_asset/My_mobilenet.pb";
//    private static final String OBJECT_MODEL_FILE = "file:///android_asset/6_opt_mobilenet_v1_1.0_224.pb";

    private static final String Initial_MODEL_FILE = "file:///android_asset/initial.pb";
    private static final String Next_State_MODEL_FILE = "file:///android_asset/next_state.pb";
    private static final String Belief_Update_MODEL_FILE = "file:///android_asset/belief_update.pb";

    private static final String LABEL_FILE =
            "file:///android_asset/labels.txt";

    private static Context context;
    private String[] initOutputNames = new String[3];
    private String[] nextOutputNames = new String[5];
    private String[] beliefOutputNames = new String[2];

    long startTime,endTime,eplasedTime;

    public static Context getContext(){

        return context;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bebop);

        initIHM();

        Intent intent = getIntent();
        ARDiscoveryDeviceService service = intent.getParcelableExtra(DeviceListActivity.EXTRA_DEVICE_SERVICE);
        mBebopDrone = new BebopDrone(this, service);
        mBebopDrone.addListener(mBebopListener);

        context = this.getApplicationContext();

        imageView = (ImageView) findViewById(R.id.frontview);

        initTensorFlowAndLoadModel();

        initStateModel();
        nextStateModel();
        beliefUpdateModel();
    }

    private void initTensorFlowAndLoadModel() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    classifier = TensorFlowImageClassifier.create(getAssets(), MODEL_FILE, LABEL_FILE,
                            INPUT_SIZE, IMAGE_MEAN, IMAGE_STD, INPUT_NAME, OUTPUT_NAME);
                } catch (final Exception e) {
                    //if they aren't found, throw an error!
                    throw new RuntimeException("Error initializing classifiers!", e);
                }
            }
        }).start();
    }

    private void initStateModel() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    initialStateHandler = initialStateHandler.create(getAssets(),Initial_MODEL_FILE,initOutputNames
                            );
                } catch (final Exception e) {
                    //if they aren't found, throw an error!
                    throw new RuntimeException("Error initializing init states!", e);
                }
            }
        }).start();
    }

    private void nextStateModel() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    nextStateHandler = nextStateHandler.create(getAssets(),Next_State_MODEL_FILE,nextOutputNames);
                } catch (final Exception e) {
                    //if they aren't found, throw an error!
                    throw new RuntimeException("Error initializing classifiers!", e);
                }
            }
        }).start();
    }

    private void beliefUpdateModel() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    beliefUpdateHandler = beliefUpdateHandler.create(getAssets(),Belief_Update_MODEL_FILE,beliefOutputNames);
                } catch (final Exception e) {
                    //if they aren't found, throw an error!
                    throw new RuntimeException("Error initializing classifiers!", e);
                }
            }
        }).start();
    }

    @Override
    protected void onStart() {
        super.onStart();

        // show a loading view while the bebop drone is connecting
        if ((mBebopDrone != null) && !(ARCONTROLLER_DEVICE_STATE_ENUM.ARCONTROLLER_DEVICE_STATE_RUNNING.equals(mBebopDrone.getConnectionState())))
        {
            mConnectionProgressDialog = new ProgressDialog(this, R.style.AppCompatAlertDialogStyle);
            mConnectionProgressDialog.setIndeterminate(true);
            mConnectionProgressDialog.setMessage("Connecting ...");
            mConnectionProgressDialog.setCancelable(false);
            mConnectionProgressDialog.show();

            // if the connection to the Bebop fails, finish the activity
            if (!mBebopDrone.connect()) {
                finish();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (mBebopDrone != null)
        {
            mConnectionProgressDialog = new ProgressDialog(this, R.style.AppCompatAlertDialogStyle);
            mConnectionProgressDialog.setIndeterminate(true);
            mConnectionProgressDialog.setMessage("Disconnecting ...");
            mConnectionProgressDialog.setCancelable(false);
            mConnectionProgressDialog.show();

            if (!mBebopDrone.disconnect()) {
                finish();
            }
        }
    }

    @Override
    public void onDestroy()
    {
        mBebopDrone.dispose();
        super.onDestroy();
    }

    private void initIHM() {

        mVideoView = (BebopVideoView) findViewById(R.id.videoView);

        findViewById(R.id.testFlight).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {

                tree_search_mode = true;

                mBebopDrone.lookDown();

                initOutput initOutput = initialStateHandler.getInitialState();
                mBebopDrone.updateInitialState(initOutput);

                nextOutput first_output = mBebopDrone.updateNextState(nextStateHandler);

                float[] current_coords = mBebopDrone.runSearch(first_output.getNext_coords());

                mCurrentCoords.setText(Arrays.toString(current_coords));
            }
        });

        findViewById(R.id.cnntest).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {

                test_mode = true;

                initOutput initOutput = initialStateHandler.getInitialState();

                nextOutput nextOutput = nextStateHandler.getState(
                        initOutput.getState(),
                        initOutput.getBelief(),
                        initOutput.getOccupancy());

                int[] obs = new int[1];
                obs[0] = 1;

                beliefUpdateOutput beliefOutput = beliefUpdateHandler.getState(
                        nextOutput.getNext_pos(),
                        obs,
                        nextOutput.getObs_level(),
                        initOutput.getBelief(),
                        initOutput.getOccupancy());

                mFlyingStatus.setText(Arrays.toString(beliefOutput.getNextBelief()));

            }
        });

        mEmergencyBt = (Button) findViewById(R.id.emergencyBt);
        mEmergencyBt.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {

                mBebopDrone.emergency();
            }
        });

        mTakeOffLandBt = (Button) findViewById(R.id.takeOffOrLandBt);
        mTakeOffLandBt.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                switch (mBebopDrone.getFlyingState()) {
                    case ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_LANDED:
                        mBebopDrone.takeOff();
                        break;
                    case ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_FLYING:
                        mBebopDrone.land();
                        break;
                    case ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_HOVERING:
                        mBebopDrone.land();
                        break;
                    default:
                }
            }
        });

        findViewById(R.id.takePictureBt).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {

//                picture_mode = true;
//                mBebopDrone.takePicture();
//                mBebopDrone.testDirection();
                screenshot = true;
            }
        });

        mBatteryLabel = (TextView) findViewById(R.id.batteryLabel);
        mFlyingStatus = (TextView) findViewById(R.id.flyingstatus);
        mCurrentCoords = (TextView) findViewById(R.id.location);
        mCNNstatus = (TextView) findViewById(R.id.cnn);
        mSpeedX = (TextView) findViewById(R.id.speedX);
        mSpeedY = (TextView) findViewById(R.id.speedY);
        mSpeedZ = (TextView) findViewById(R.id.speedZ);
        mStop = (TextView) findViewById(R.id.stop);
    }

    private final BebopDrone.Listener mBebopListener = new BebopDrone.Listener() {

        @Override
        public void configureDecoder(ARControllerCodec codec) {
            mVideoView.configureDecoder(codec);
        }

        @Override
        public void onFrameReceived(ARFrame frame) {

            Image ss = mVideoView.displayFrame(frame,screenshot);
            if(ss!=null){
                ByteBuffer buffer = ss.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.capacity()];
                buffer.get(bytes);
                Bitmap bitmapImage = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, null);
                imageView.setImageBitmap(bitmapImage);
                screenshot = false;
                ss.close();
            }

        }

        @Override
        public void onDroneConnectionChanged(ARCONTROLLER_DEVICE_STATE_ENUM state) {
            switch (state)
            {
                case ARCONTROLLER_DEVICE_STATE_RUNNING:
                    mConnectionProgressDialog.dismiss();
                    break;

                case ARCONTROLLER_DEVICE_STATE_STOPPED:
                    // if the deviceController is stopped, go back to the previous activity
                    mConnectionProgressDialog.dismiss();
                    finish();
                    break;

                default:
                    break;
            }
        }

        @Override
        public void onMovebyEnd(ARCOMMANDS_ARDRONE3_PILOTINGEVENT_MOVEBYEND_ERROR_ENUM moveByEnd){

            if(moveByEnd!=ARCOMMANDS_ARDRONE3_PILOTINGEVENT_MOVEBYEND_ERROR_ENUM.ARCOMMANDS_ARDRONE3_PILOTINGEVENT_MOVEBYEND_ERROR_OK){

//                                moveby failed. notify and land.
                mFlyingStatus.setText("moveBy failed.");
                mBebopDrone.land();
            }else{

                mFlyingStatus.setText("Test Flight Ongoing.");

//                the drone takes a snapshot after it reaches destination.
                mBebopDrone.takePicture();

            }
        }

        @Override
        public void onSpeedChanged(float x, float y, float z){

            mSpeedX.setText(String.format("%.02f", 100*x));
            mSpeedY.setText(String.format("%.02f", 100*y));
            mSpeedZ.setText(String.format("%.02f", 100*z));

        }

        @Override
        public void onBatteryChargeChanged(int batteryPercentage) {
            mBatteryLabel.setText(String.format("%d%%", batteryPercentage));
        }

        @Override
        public void onPilotingStateChanged(ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_ENUM state) {

            switch (state) {
                case ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_LANDED:
                    mFlyingStatus.setText("Drone Landed.");
                    mTakeOffLandBt.setText("Take Off");
                    mTakeOffLandBt.setEnabled(true);
//                    mDownloadBt.setEnabled(true);
                    break;
                case ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_FLYING:
                case ARCOMMANDS_ARDRONE3_PILOTINGSTATE_FLYINGSTATECHANGED_STATE_HOVERING:
                    mTakeOffLandBt.setText("Land");
                    mTakeOffLandBt.setEnabled(true);
//                    mDownloadBt.setEnabled(false);
                    break;
                default:
                    mTakeOffLandBt.setEnabled(false);
//                    mDownloadBt.setEnabled(false);
            }
        }

        @Override
        public void onPictureTaken(ARCOMMANDS_ARDRONE3_MEDIARECORDEVENT_PICTUREEVENTCHANGED_ERROR_ENUM error) {

//            Log.i(TAG, "Picture has been taken and downloading.");
            mFlyingStatus.setText("Picture taken.");
            startTime = System.currentTimeMillis();

            new Thread(new Runnable() {
                @Override
                public void run() {

                    mBebopDrone.getLastPicture();
                }
            }).start();
        }

        @Override
        public void onPositionChanged(double X, double Y, double Z){

//            if(X==500){
//                mPositionX.setText("Not Available");
//            }else{
//                    String latitude = Double.toString(X);
//                    mPositionX.setText(latitude);
//            }
//            if(Y==500){
//                mPositionY.setText("Not Available");
//            }else{
//                String longitude = Double.toString(Y);
//                mPositionY.setText(longitude);
//            }
//            if(Z==500){
//                mPositionZ.setText("Not Available");
//            }else{
//                String altitude = Double.toString(Z);
//                mPositionZ.setText(altitude);
//            }
        }

        @Override
        public void onMatchingMediasFound(int nbMedias) {

        }

        @Override
        public void onDownloadProgressed(String mediaName, int progress) {

        }

        @Override
        public void onDownloadComplete(String mediaName) {

            mFlyingStatus.setText("download complete");
//            endTime = System.currentTimeMillis();
//            long elapsedTime = endTime-startTime;
//            mFlyingStatus.setText(String.valueOf(elapsedTime));

//            Log.d(TAG,"download complete.");

            if(test_mode==true){

                mFlyingStatus.setText("Test Mode");

            }else if(picture_mode==true){

                mFlyingStatus.setText("Picture Mode");

                int[] res_cnn = mBebopDrone.runAZnet(classifier,mediaName);

                String file_path = storage_location + mediaName;

                pictureBitmap = BitmapFactory.decodeFile(file_path);
                pictureBitmap = Bitmap.createScaledBitmap(pictureBitmap, INPUT_SIZE, INPUT_SIZE, false);
                imageView.setImageBitmap(pictureBitmap);

                mCNNstatus.setText(String.valueOf(res_cnn[0]));

            }else if(tree_search_mode==true){

                mFlyingStatus.setText("Search Mode");
//                int[] res_cnn = new int[1];
                //                run CNN on downloaded image.
                int[] res_cnn = mBebopDrone.runAZnet(classifier,mediaName);
                mCNNstatus.setText(Arrays.toString(res_cnn));

//                if(mBebopDrone.idleSensing()){
//
//                    res_cnn[0] = 0;
//                    mCNNstatus.setText(Arrays.toString(res_cnn));
//                }else{
//                    //                run CNN on downloaded image.
//                    res_cnn = mBebopDrone.runAZnet(classifier,mediaName);
//                }

                String file_path = storage_location + mediaName;

                pictureBitmap = BitmapFactory.decodeFile(file_path);
                pictureBitmap = Bitmap.createScaledBitmap(pictureBitmap, INPUT_SIZE, INPUT_SIZE, false);
                imageView.setImageBitmap(pictureBitmap);

//                Execute belief update.
                mBebopDrone.updateBelief(beliefUpdateHandler,res_cnn);

//                get next target location.
                nextOutput nextOutput = mBebopDrone.updateNextState(nextStateHandler);

                mStop.setText(String.valueOf(nextOutput.getStop()[0]));

//                Log.d(TAG,String.valueOf(nextOutput.getStop()[0]));

                if(nextOutput.getStop()[0]!=0){

                    mFlyingStatus.setText("Search Success. Landing.");

//                    Log.i(TAG,"stopped");
                    mBebopDrone.land();
                }else{
                    //                run the next move.
                    mFlyingStatus.setText("next target: " + Arrays.toString(nextOutput.getNext_coords()));
//                    Log.d(TAG,"next target: " + Arrays.toString(nextOutput.getNext_coords()));
                    float[] current_coords = mBebopDrone.runSearch(nextOutput.getNext_coords());
                    mCurrentCoords.setText(Arrays.toString(current_coords));
                }

            }else{

                mFlyingStatus.setText("no subsequent action after downloading.");
            }
        }

    };
}
