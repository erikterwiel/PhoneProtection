package erikterwiel.phoneprotection.Services;

import android.app.IntentService;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.support.annotation.Nullable;
import android.util.Log;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.CognitoCachingCredentialsProvider;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferObserver;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState;
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.rekognition.AmazonRekognitionClient;
import com.amazonaws.services.rekognition.model.BoundingBox;
import com.amazonaws.services.rekognition.model.CompareFacesMatch;
import com.amazonaws.services.rekognition.model.CompareFacesRequest;
import com.amazonaws.services.rekognition.model.CompareFacesResult;
import com.amazonaws.services.rekognition.model.ComparedFace;
import com.amazonaws.services.rekognition.model.DetectLabelsRequest;
import com.amazonaws.services.rekognition.model.DetectLabelsResult;
import com.amazonaws.services.rekognition.model.Image;
import com.amazonaws.services.rekognition.model.Label;
import com.amazonaws.services.rekognition.model.S3Object;
import com.amazonaws.services.sns.AmazonSNSClient;
import com.amazonaws.services.sns.model.GetTopicAttributesRequest;
import com.amazonaws.services.sns.model.PublishRequest;
import com.amazonaws.util.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import erikterwiel.phoneprotection.FileCompressor;
import erikterwiel.phoneprotection.MyAdminReceiver;
import erikterwiel.phoneprotection.Singletons.Protection;
import erikterwiel.phoneprotection.Singletons.Rekognition;
import erikterwiel.phoneprotection.Singletons.S3;

import static erikterwiel.phoneprotection.Keys.DynamoDBKeys.POOL_ID_UNAUTH;
import static erikterwiel.phoneprotection.Keys.DynamoDBKeys.POOL_REGION;
import static erikterwiel.phoneprotection.Keys.SNSKeys.ACCOUNT;
import static erikterwiel.phoneprotection.Keys.SNSKeys.REGION;

public class DetectionService extends IntentService {

    private static final String TAG = "DetectionService.java";
    private static final String PATH_STREAM = "sdcard/Pictures/PhoneProtection/Stream";
    private static final String BUCKET_NAME = "phoneprotectionpictures";
    private static final float CONFIDENCE_THRESHOLD = 70F;

    private ArrayList<String> mUserList = new ArrayList<>();
    private ArrayList<String> mBucketFiles = new ArrayList<>();
    private AWSCredentialsProvider mCredentialsProvider;
    private AmazonRekognitionClient mRekognition;
    private TransferUtility mTransferUtility;
    private String mUsername;
    private Intent mIntent;
    private SharedPreferences mDatabase;

    public DetectionService() {
        super("DetectionService");
    }

    @Override
    public void onHandleIntent(Intent intent) {
        Log.i(TAG, "onHandleIntent() called");

        Protection.getInstance().enableScanning();

        mDatabase = getSharedPreferences("settings", MODE_PRIVATE);
        mCredentialsProvider = new CognitoCachingCredentialsProvider(
                this,
                POOL_ID_UNAUTH,
                Regions.fromName(POOL_REGION));
        mTransferUtility = S3.getInstance().getTransferUtility();
        mRekognition = Rekognition.getInstance().getRekognitionClient();

        mIntent = intent;
        mUsername = intent.getStringExtra("username");
        int size = intent.getIntExtra("size", 0);
        for (int i = 0; i < size; i++) {
            mUserList.add(intent.getStringExtra("user" + i));
            mBucketFiles.add(mUsername + "/" + intent.getStringExtra("bucketfiles" + i));
        }

        // Captures picture and saves it
        SurfaceTexture surfaceTexture = new SurfaceTexture(0);
        Camera camera = null;
        Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
        Camera.getCameraInfo(1, cameraInfo);
        try {
            camera = Camera.open(1);
        } catch (RuntimeException e) {
            Log.i(TAG, "Camera 1 not available");
        }
        try {
            if (camera == null) {
                Log.i(TAG, "Could not get camera instance");
            } else {
                try {
                    camera.setPreviewTexture(surfaceTexture);
                    camera.startPreview();
                } catch (Exception e) {
                    Log.i(TAG, "Could not set the surface preview texture");
                }
                camera.takePicture(null, null, new Camera.PictureCallback() {
                    @Override
                    public void onPictureTaken(byte[] bytes, Camera camera) {
                        File folder = new File(PATH_STREAM);
                        if (!folder.exists()) folder.mkdir();
                        File file = new File(folder, "Stream.jpg");
                        try {
                            FileOutputStream fos = new FileOutputStream(file);
                            fos.write(bytes);
                            fos.close();
                            Log.i(TAG, "Image saved");
                        } catch (Exception e) {
                            Log.i(TAG, "Image could not be saved");
                        }
                        camera.release();
                    }
                });
            }
        } catch (Exception e) {
            camera.release();
            e.printStackTrace();
        }

        // Compares captured and saved pictures to S3 database
        boolean isFace = false;
        boolean isUser = false;
        try {

            // Turns .jpg file to Amazon Image file
            Thread.sleep(1500);
            FileCompressor fileCompressor = new FileCompressor();
            InputStream inputStream = new FileInputStream(
                    fileCompressor.compressImage(PATH_STREAM + "/Stream.jpg", this));
            ByteBuffer imageBytes = ByteBuffer.wrap(IOUtils.toByteArray(inputStream));
            Image targetImage = new Image().withBytes(imageBytes);

            // Checks if file contains a face
            DetectLabelsRequest detectLabelsRequest = new DetectLabelsRequest()
                    .withImage(targetImage)
                    .withMinConfidence(CONFIDENCE_THRESHOLD);
            DetectLabelsResult detectLabelsResult = mRekognition.detectLabels(detectLabelsRequest);
            List<Label> labels = detectLabelsResult.getLabels();
            for (int i = 0; i < labels.size(); i++) {
                String label = labels.get(i).getName();
                if (label.equals("People") || label.equals("Person") || label.equals("Human"))
                    isFace = true;
                Log.i(TAG, labels.get(i).getName() + ":" + labels.get(i).getConfidence().toString());
            }

            // Compares faces if above fail contains a face
            if (isFace) {
                for (int i = 0; i < mUserList.size(); i++) {
                    String[] inputNameSplit = mBucketFiles.get(i).split("/");
                    Log.i(TAG, "Attempting to compare faces");
                    CompareFacesRequest compareFacesRequest = new CompareFacesRequest()
                            .withSourceImage(new Image()
                                    .withS3Object(new S3Object()
                                            .withName(mBucketFiles.get(i))
                                            .withBucket(BUCKET_NAME)))
                            .withTargetImage(targetImage)
                            .withSimilarityThreshold(CONFIDENCE_THRESHOLD);
                    Log.i(TAG, "Comparing face to " + mBucketFiles.get(i) + " in " + BUCKET_NAME);
                    CompareFacesResult compareFacesResult =
                            mRekognition.compareFaces(compareFacesRequest);
                    List<CompareFacesMatch> faceDetails = compareFacesResult.getFaceMatches();
                    for (int j = 0; j < faceDetails.size(); j++) {
                        ComparedFace face = faceDetails.get(j).getFace();
                        BoundingBox position = face.getBoundingBox();
                        Log.i(TAG, "Face at " + position.getLeft().toString()
                                + " " + position.getTop()
                                + " matches with " + face.getConfidence().toString()
                                + "% confidence.");
                        isUser = true;
                    }
                    if (isUser) {
                        Protection.getInstance().pauseProtection();
                        break;
                    }
                }
                if (!isUser) lockDown();
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void lockDown() {
        Log.i(TAG, "lockDown() activated");

        // Upload picture of intruder to S3
        String randomID = UUID.randomUUID().toString();
        FileCompressor fileCompressor = new FileCompressor();
        File file = new File(fileCompressor.compressImage(PATH_STREAM + "/Stream.jpg", this));
        TransferObserver observer = mTransferUtility.upload(
                BUCKET_NAME,
                mIntent.getStringExtra("username") + "/Intruder/" + randomID + ".jpg",
                file);
        Log.i(TAG, "Uploading");
        observer.setTransferListener(new UploadListener());

        // Plays siren if selected
        if (mDatabase.getBoolean("siren", false)) {
            Log.i(TAG, "Starting SirenService");
            Intent sirenIntent = new Intent(this, SirenService.class);
            startService(sirenIntent);
        }

        // Vibrates phone
        Vibrator vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        vibrator.vibrate(VibrationEffect.createOneShot(3000,255));

        // Emails and texts owner of phone
        AmazonSNSClient snsClient = new AmazonSNSClient(mCredentialsProvider);
        snsClient.setRegion(Region.getRegion(Regions.US_EAST_1));
        String topicName = mIntent.getStringExtra("email");
        topicName = topicName.replaceAll("\\.", "");
        topicName = topicName.replaceAll("@", "");
        topicName = topicName.replaceAll(":", "");
        String msg = "Face Lock has identified this individual using your phone.\n" +
                "https://s3.amazonaws.com/phoneprotectionpictures/" +
                mUsername + "/Intruder/" + randomID + ".jpg\n\n" +
                "Go to http://facelock.co/ to locate your phone";
        String subject = "URGENT: Someone Has Your Phone";
        PublishRequest publishRequest = new PublishRequest(
                "arn:aws:sns:" + REGION + ":" + ACCOUNT + ":" + topicName,
                msg, subject);
        snsClient.publish(publishRequest);

        // Starts rapid location services
        Intent trackerIntent = new Intent(this, TrackerService.class);
        trackerIntent.putExtra("username", mUsername);
        Log.i(TAG, "Passing " + mUsername + " to TrackerService");
//        startService(trackerIntent);

        // Lock down phone
        DevicePolicyManager deviceManager =
                (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName compName = new ComponentName(this, MyAdminReceiver.class);
        if (deviceManager.isAdminActive(compName)) deviceManager.lockNow();

        // Shutdown service
        Protection.getInstance().disableProtection();
    }

    private class UploadListener implements TransferListener {

        @Override
        public void onStateChanged(int id, TransferState state) {
            Log.i(TAG, state + "");
        }

        @Override
        public void onProgressChanged(int id, long bytesCurrent, long bytesTotal) {
            int percentage = (int) (bytesCurrent / bytesTotal * 100);
            Log.i(TAG, Integer.toString(percentage) + "% uploaded");
        }

        @Override
        public void onError(int id, Exception ex) {
            ex.printStackTrace();
            Log.i(TAG, "Error detected");
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
