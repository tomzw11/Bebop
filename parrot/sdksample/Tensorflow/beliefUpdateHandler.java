package com.parrot.sdksample.Tensorflow;

import android.content.res.AssetManager;
import android.os.Trace;

import org.tensorflow.contrib.android.TensorFlowInferenceInterface;

public class beliefUpdateHandler {

    private static final String TAG = "beliefUpdateHandler";

    public class beliefUpdateOutput{

        private final float[] next_belief;
        private final int[] next_occupancy;

        public beliefUpdateOutput(
                final float[] next_belief, final int[] next_occupancy) {
            this.next_belief = next_belief;
            this.next_occupancy = next_occupancy;
        }

        public float[] getNextBelief(){
            return next_belief;
        }
        public int[] getNextOccupancy(){
            return next_occupancy;
        }
    }

    // Config values.
    private String[] outputNames = {"next_belief","next_occupancy"};
    private String[] inputNames = {"pos","obs","obs_level","belief","occupancy"};

    // Pre-allocated buffers.
    private float[] next_belief;
    private int[] next_occupancy;

    private boolean logStats = false;

    private TensorFlowInferenceInterface inferenceInterface;

    private beliefUpdateHandler() {}

    public static beliefUpdateHandler create(
            AssetManager assetManager,
            String modelFilename,
            String[] outputNames) {

        beliefUpdateHandler c = new beliefUpdateHandler();
        c.outputNames = outputNames;

        c.inferenceInterface = new TensorFlowInferenceInterface(assetManager, modelFilename);

        // Pre-allocate buffers.
        c.outputNames[0] = "next_belief";
        c.outputNames[1] = "next_occupancy";

        c.next_belief = new float[49];
        c.next_occupancy = new int[343];

        return c;
    }

    public beliefUpdateOutput getState(int[] pos, int[] obs, int[] obs_level, float[] belief, int[] occupancy) {
        Trace.beginSection("belief update");

        Trace.beginSection("feed");
        inferenceInterface.feed(inputNames[0],pos);
        inferenceInterface.feed(inputNames[1],obs);
        inferenceInterface.feed(inputNames[2],obs_level);
        inferenceInterface.feed(inputNames[3],belief,49);
        inferenceInterface.feed(inputNames[4],occupancy,343);
//        TODO:add automatic size.
        Trace.endSection();

        Trace.beginSection("run");
        inferenceInterface.run(outputNames, logStats);
        Trace.endSection();

        // Copy the output Tensor back into the output array.
        Trace.beginSection("fetch");
        inferenceInterface.fetch(outputNames[0], next_belief);
        inferenceInterface.fetch(outputNames[1], next_occupancy);
        Trace.endSection();

        beliefUpdateOutput bo = new beliefUpdateOutput(next_belief,next_occupancy);

        Trace.endSection(); // "initial"

        return bo;
    }

}
