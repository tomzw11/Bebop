package com.parrot.sdksample.activity;

// Communicating with remote server for navigation commands.

import android.util.Log;

import java.net.*;
import java.io.*;
import java.util.concurrent.Callable;

public class Navigation implements Callable<String>{

    private String mediaName;

    private static final String TAG = "Navigation";
    private static final String server_ip = "137.110.147.11";
    private static String storage_location = "/storage/emulated/0/ARSDKMedias/";

    private static final int send_port = 8887;
    private static final int receive_port = 8888;

    public Navigation(String mediaName){
        this.mediaName = mediaName;
    }

    @Override
    public String call() throws Exception {

//        //send image to server via socket.
//        int res = -1;

        String res = "none";

        try{

            String file_path = storage_location + mediaName;
//            Log.i(TAG, "file path: "+ file_path);

//            Socket s_client = new Socket(server_ip, send_port);
//            s_client.setSoTimeout(100000);
//            Log.i(TAG,"client socket created.");
//
//            sendImage(s_client,file_path);
//            s_client.close();
//            Log.i(TAG,"client socket closed.");
//
//            Socket s_receive = new Socket(server_ip, receive_port);
//            s_receive.setSoTimeout(100000);
//            Log.i(TAG,"socket for reply created.");
//
//            String message = getResponse(s_receive);
//            s_receive.close();
//            Log.i(TAG,"socket for reply closed.");
//
//            res = Integer.parseInt(message);

        }catch(Exception e){
            Log.i(TAG, "image transfer failed: " + e);
            e.printStackTrace();
        }
        return res;
    }


    static void sendImage(Socket socket,String image_path){

        try{
            int i;
            FileInputStream fis = new FileInputStream (image_path);
            BufferedOutputStream os = new BufferedOutputStream(socket.getOutputStream());

            while ((i = fis.read()) > -1)

                os.write(i);
            Log.i(TAG, "Image sent.");

            fis.close();
            os.close();

        } catch (Exception exception)
        {
            exception.printStackTrace();
        }
    }

    String getResponse(Socket socket){

        String message = "-1";

        try{

            while(true){

                InputStream is = socket.getInputStream();
                InputStreamReader isr = new InputStreamReader(is);
                BufferedReader br = new BufferedReader(isr);
                message = br.readLine();
                Log.i(TAG,"Response " + " " + message);
                is.close();
                break;
            }

        } catch (Exception exception)
        {
            exception.printStackTrace();
        }finally {
            return message;
        }

    }

}
