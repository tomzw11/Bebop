package com.parrot.sdksample.Tensorflow;

import android.content.res.AssetManager;
import android.os.Trace;

import org.tensorflow.contrib.android.TensorFlowInferenceInterface;

public class initialStateHandler {

    private static final String TAG = "initialStateHandler";

        public class initOutput{

            private final int[] state;
            private final float[] belief;
            private final int[] occupancy;

            public initOutput(
                    final int[] state, final float[] belief, final int[] occupancy) {
                this.state = state;
                this.belief = belief;
                this.occupancy = occupancy;
            }

            public int[] getState(){
                return state;
            }
            public float[] getBelief(){
                return belief;
            }
            public int[] getOccupancy(){
                return occupancy;
            }
        }

    // Pre-allocated buffers.
    private int[] output_initial;
    private float[] output_belief;
    private int[] output_occupancy;

    private String[] outputNames = new String[3];

    private boolean logStats = false;

    private TensorFlowInferenceInterface inferenceInterface;

    private initialStateHandler() {}

    public static initialStateHandler create(
            AssetManager assetManager,
            String modelFilename,
            String[] outputNames) {

        initialStateHandler c = new initialStateHandler();
        c.outputNames = outputNames;

        c.inferenceInterface = new TensorFlowInferenceInterface(assetManager, modelFilename);

        // Pre-allocate buffers.
        c.outputNames[0] = "init_state";
        c.outputNames[1] = "init_belief";
        c.outputNames[2] = "init_occupancy";

        c.output_initial = new int[1];
        c.output_belief = new float[49];
        c.output_occupancy = new int[343];

        return c;
    }

    public initOutput getInitialState() {
        Trace.beginSection("initial");
        // Run the inference call.
        Trace.beginSection("run");
        inferenceInterface.run(outputNames, logStats);
        Trace.endSection();

        // Copy the output Tensor back into the output array.
        Trace.beginSection("fetch");
        inferenceInterface.fetch(outputNames[0], output_initial);
        inferenceInterface.fetch(outputNames[1], output_belief);
        inferenceInterface.fetch(outputNames[2], output_occupancy);
        Trace.endSection();

        initOutput io = new initOutput(output_initial,output_belief,output_occupancy);

        Trace.endSection();
        return io;
    }

}
