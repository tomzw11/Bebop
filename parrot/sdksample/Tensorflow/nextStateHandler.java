package com.parrot.sdksample.Tensorflow;

import android.content.res.AssetManager;
import android.os.Trace;
import android.util.Log;

import org.tensorflow.contrib.android.TensorFlowInferenceInterface;

import java.util.Arrays;

public class nextStateHandler {

    private static final String TAG = "nextStateHandler";

    public class nextOutput{

        private final int[] next_state;
        private final int[] stop;
        private final int[] obs_level;
        private final int[] next_pos;
        private final float[] next_coords;

        public nextOutput(
                final int[] next_state, final int[] stop,
                final int[] obs_level, final int[] next_pos, final float[] next_coords) {
            this.next_state = next_state;
            this.stop = stop;
            this.obs_level = obs_level;
            this.next_pos = next_pos;
            this.next_coords = next_coords;
        }

        public int[] getNextState(){
            return next_state;
        }
        public int[] getStop(){
            return stop;
        }
        public int[] getObs_level(){
            return obs_level;
        }
        public int[] getNext_pos(){
            return next_pos;
        }
        public float[] getNext_coords(){
            return next_coords;
        }
    }

    // Config values.
    private String[] outputNames = {"next_state","stop","obs_level","next_pos","next_coords"};
    private String[] inputNames = {"state","belief","occupancy"};

    // Pre-allocated buffers.
    private int[] next_state;
    private int[] stop;
    private int[] obs_level;
    private int[] next_pos;
    private float[] next_coords;

    private boolean logStats = false;

    private TensorFlowInferenceInterface inferenceInterface;

    private nextStateHandler() {}

    public static nextStateHandler create(
            AssetManager assetManager,
            String modelFilename,
            String[] outputNames) {

        nextStateHandler c = new nextStateHandler();
        c.outputNames = outputNames;

        c.inferenceInterface = new TensorFlowInferenceInterface(assetManager, modelFilename);

        // Pre-allocate buffers.
        c.outputNames[0] = "next_state";
        c.outputNames[1] = "stop";
        c.outputNames[2] = "obs_level";
        c.outputNames[3] = "next_pos";
        c.outputNames[4] = "next_coords";

        c.next_state = new int[1];
        c.stop = new int[1];
        c.obs_level = new int[1];
        c.next_pos = new int[1];
        c.next_coords = new float[3]; //TODO:modify size.

        return c;
    }

    public nextOutput getState(int[] init_state, float[] init_belief, int[] init_occupancy) {
        Trace.beginSection("next_state");

        // Copy the input data into TensorFlow.
        Trace.beginSection("feed");
        inferenceInterface.feed(inputNames[0],init_state);
        inferenceInterface.feed(inputNames[1],init_belief,49);
        inferenceInterface.feed(inputNames[2],init_occupancy,343);
//        TODO:add automatic size.
        Trace.endSection();

        // Run the inference call.
        Trace.beginSection("run");
        inferenceInterface.run(outputNames, logStats);
        Trace.endSection();

        // Copy the output Tensor back into the output array.
        Trace.beginSection("fetch");
        inferenceInterface.fetch(outputNames[0], next_state);
        inferenceInterface.fetch(outputNames[1], stop);
        inferenceInterface.fetch(outputNames[2], obs_level);
        inferenceInterface.fetch(outputNames[3], next_pos);
        inferenceInterface.fetch(outputNames[4], next_coords);
        Trace.endSection();

        nextOutput no = new nextOutput(next_state,stop,obs_level,next_pos,next_coords);

        Trace.endSection(); // "initial"
        return no;
    }

}
