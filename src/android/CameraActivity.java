package com.cordovaplugincamerapreview;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.app.Fragment;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Bitmap.CompressFormat;
import android.media.ExifInterface;
import android.util.Base64;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.Camera.PictureCallback;
import android.hardware.Camera.ShutterCallback;
import android.os.Bundle;
import android.util.Log;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.os.Environment;
import java.io.File;

import org.apache.cordova.LOG;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.Exception;
import java.lang.Integer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Arrays;

public class CameraActivity extends Fragment {

  public interface CameraPreviewListener {
    void onPictureTaken(String originalPicture, JSONObject metadata);
    void onPictureTakenError(String message);
    void onFocusSet(int pointX, int pointY);
    void onFocusSetError(String message);
    void onCameraStarted();
  }

  private CameraPreviewListener eventListener;
  private static final String TAG = "CameraActivity";
  public FrameLayout mainLayout;
  public FrameLayout frameContainerLayout;

  private Preview mPreview;
  private boolean canTakePicture = true;

  private View view;
  private Camera.Parameters cameraParameters;
  private Camera mCamera;
  private int numberOfCameras;
  private int cameraCurrentlyLocked;
  private int currentQuality;

  // The first rear facing camera
  private int defaultCameraId;
  public String defaultCamera;
  public boolean tapToTakePicture;
  public boolean dragEnabled;
  public boolean tapToFocus;
  public String dataDir;

  public int width;
  public int height;
  public int x;
  public int y;

  public void setEventListener(CameraPreviewListener listener) {
    eventListener = listener;
  }

  private String appResourcesPackage;

  @Override
  public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
    appResourcesPackage = getActivity().getPackageName();

    // Inflate the layout for this fragment
    view = inflater.inflate(getResources().getIdentifier("camera_activity", "layout", appResourcesPackage), container, false);
    createCameraPreview();
    return view;
  }

  public void setRect(int x, int y, int width, int height) {
    this.x = x;
    this.y = y;
    this.width = width;
    this.height = height;
  }

  private void createCameraPreview() {
    if (mPreview == null) {
      setDefaultCameraId();

      //set box position and size
      FrameLayout.LayoutParams layoutParams = new FrameLayout.LayoutParams(width, height);
      layoutParams.setMargins(x, y, 0, 0);
      frameContainerLayout = (FrameLayout) view.findViewById(getResources().getIdentifier("frame_container", "id", appResourcesPackage));
      frameContainerLayout.setLayoutParams(layoutParams);

      //video view
      mPreview = new Preview(getActivity());
      mainLayout = (FrameLayout) view.findViewById(getResources().getIdentifier("video_view", "id", appResourcesPackage));
      mainLayout.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
      mainLayout.addView(mPreview);
      mainLayout.setEnabled(false);

      final GestureDetector gestureDetector = new GestureDetector(getActivity().getApplicationContext(), new TapGestureDetector());

      getActivity().runOnUiThread(new Runnable() {
        @Override
        public void run() {
          frameContainerLayout.setClickable(true);
          frameContainerLayout.setOnTouchListener(new View.OnTouchListener() {

            private int mLastTouchX;
            private int mLastTouchY;
            private int mPosX = 0;
            private int mPosY = 0;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
              FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) frameContainerLayout.getLayoutParams();


              boolean isSingleTapTouch = gestureDetector.onTouchEvent(event);
              if (event.getAction() != MotionEvent.ACTION_MOVE && isSingleTapTouch) {
                if (tapToTakePicture && tapToFocus) {
                  setFocusArea((int) event.getX(0), (int) event.getY(0), new Camera.AutoFocusCallback() {
                    public void onAutoFocus(boolean success, Camera camera) {
                      if (success) {
                        takePicture(0, 0, 85);
                      } else {
                        Log.d(TAG, "onTouch:" + " setFocusArea() did not suceed");
                      }
                    }
                  });

                } else if (tapToTakePicture) {
                  takePicture(0, 0, 85);

                } else if (tapToFocus) {
                  setFocusArea((int) event.getX(0), (int) event.getY(0), new Camera.AutoFocusCallback() {
                    public void onAutoFocus(boolean success, Camera camera) {
                      if (success) {
                        // A callback to JS might make sense here.
                      } else {
                        Log.d(TAG, "onTouch:" + " setFocusArea() did not suceed");
                      }
                    }
                  });
                }
                return true;
              } else {
                if (dragEnabled) {
                  int x;
                  int y;

                  switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                      if (mLastTouchX == 0 || mLastTouchY == 0) {
                        mLastTouchX = (int) event.getRawX() - layoutParams.leftMargin;
                        mLastTouchY = (int) event.getRawY() - layoutParams.topMargin;
                      } else {
                        mLastTouchX = (int) event.getRawX();
                        mLastTouchY = (int) event.getRawY();
                      }
                      break;
                    case MotionEvent.ACTION_MOVE:

                      x = (int) event.getRawX();
                      y = (int) event.getRawY();

                      final float dx = x - mLastTouchX;
                      final float dy = y - mLastTouchY;

                      mPosX += dx;
                      mPosY += dy;

                      layoutParams.leftMargin = mPosX;
                      layoutParams.topMargin = mPosY;

                      frameContainerLayout.setLayoutParams(layoutParams);

                      // Remember this touch position for the next move event
                      mLastTouchX = x;
                      mLastTouchY = y;

                      break;
                    default:
                      break;
                  }
                }
              }
              return true;
            }
          });
        }
      });
    }
  }

  private void setDefaultCameraId() {
    // Find the total number of cameras available
    numberOfCameras = Camera.getNumberOfCameras();

    int camId = defaultCamera.equals("front") ? Camera.CameraInfo.CAMERA_FACING_FRONT : Camera.CameraInfo.CAMERA_FACING_BACK;

    // Find the ID of the default camera
    Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
    for (int i = 0; i < numberOfCameras; i++) {
      Camera.getCameraInfo(i, cameraInfo);
      if (cameraInfo.facing == camId) {
        defaultCameraId = camId;
        break;
      }
    }
  }

  @Override
  public void onResume() {
    super.onResume();

    mCamera = Camera.open(defaultCameraId);

    if (cameraParameters != null) {
      mCamera.setParameters(cameraParameters);
    }

    cameraCurrentlyLocked = defaultCameraId;

    if (mPreview.mPreviewSize == null) {
      mPreview.setCamera(mCamera, cameraCurrentlyLocked);
      eventListener.onCameraStarted();
    } else {
      mPreview.switchCamera(mCamera, cameraCurrentlyLocked);
      mCamera.startPreview();
    }

    Log.d(TAG, "cameraCurrentlyLocked:" + cameraCurrentlyLocked);

    final FrameLayout frameContainerLayout = (FrameLayout) view.findViewById(getResources().getIdentifier("frame_container", "id", appResourcesPackage));

    ViewTreeObserver viewTreeObserver = frameContainerLayout.getViewTreeObserver();

    if (viewTreeObserver.isAlive()) {
      viewTreeObserver.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
        @Override
        public void onGlobalLayout() {
          frameContainerLayout.getViewTreeObserver().removeGlobalOnLayoutListener(this);
          frameContainerLayout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
          final RelativeLayout frameCamContainerLayout = (RelativeLayout) view.findViewById(getResources().getIdentifier("frame_camera_cont", "id", appResourcesPackage));

          FrameLayout.LayoutParams camViewLayout = new FrameLayout.LayoutParams(frameContainerLayout.getWidth(), frameContainerLayout.getHeight());
          camViewLayout.gravity = Gravity.CENTER_HORIZONTAL | Gravity.CENTER_VERTICAL;
          frameCamContainerLayout.setLayoutParams(camViewLayout);
        }
      });
    }
  }

  @Override
  public void onPause() {
    super.onPause();

    // Because the Camera object is a shared resource, it's very important to release it when the activity is paused.
    if (mCamera != null) {
      setDefaultCameraId();
      mPreview.setCamera(null, -1);
      mCamera.setPreviewCallback(null);
      mCamera.release();
      mCamera = null;
    }
  }

  public Camera getCamera() {
    return mCamera;
  }

  public void switchCamera() {
    // check for availability of multiple cameras
    if (numberOfCameras == 1) {
      //There is only one camera available
    } else {
      Log.d(TAG, "numberOfCameras: " + numberOfCameras);

      // OK, we have multiple cameras. Release this camera -> cameraCurrentlyLocked
      if (mCamera != null) {
        mCamera.stopPreview();
        mPreview.setCamera(null, -1);
        mCamera.release();
        mCamera = null;
      }

      Log.d(TAG, "cameraCurrentlyLocked := " + Integer.toString(cameraCurrentlyLocked));
      try {
        cameraCurrentlyLocked = (cameraCurrentlyLocked + 1) % numberOfCameras;
        Log.d(TAG, "cameraCurrentlyLocked new: " + cameraCurrentlyLocked);
      } catch (Exception exception) {
        Log.d(TAG, exception.getMessage());
      }

      // Acquire the next camera and request Preview to reconfigure parameters.
      mCamera = Camera.open(cameraCurrentlyLocked);

      if (cameraParameters != null) {
        Log.d(TAG, "camera parameter not null");

        // Check for flashMode as well to prevent error on frontward facing camera.
        List < String > supportedFlashModesNewCamera = mCamera.getParameters().getSupportedFlashModes();
        String currentFlashModePreviousCamera = cameraParameters.getFlashMode();
        if (supportedFlashModesNewCamera != null && supportedFlashModesNewCamera.contains(currentFlashModePreviousCamera)) {
          Log.d(TAG, "current flash mode supported on new camera. setting params");
                    /* mCamera.setParameters(cameraParameters);
                       The line above is disabled because parameters that can actually be changed are different from one device to another. Makes less sense trying to reconfigure them when changing camera device while those settings gan be changed using plugin methods.
                    */
        } else {
          Log.d(TAG, "current flash mode NOT supported on new camera");
        }

      } else {
        Log.d(TAG, "camera parameter NULL");
      }

      mPreview.switchCamera(mCamera, cameraCurrentlyLocked);

      mCamera.startPreview();
    }
  }

  public void setCameraParameters(Camera.Parameters params) {
    cameraParameters = params;

    if (mCamera != null && cameraParameters != null) {
      mCamera.setParameters(cameraParameters);
    }
  }

  public boolean hasFrontCamera() {
    return getActivity().getApplicationContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA_FRONT);
  }

  public static Bitmap flipBitmap(Bitmap source) {
    Matrix matrix = new Matrix();
    matrix.preScale(1.0f, -1.0f);

    return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
  }

  ShutterCallback shutterCallback = new ShutterCallback() {
    public void onShutter() {
      // do nothing, availabilty of this callback causes default system shutter sound to work
    }
  };

  public static Bitmap rotateBitmap(Bitmap source, float angle, boolean mirror) {
    Matrix matrix = new Matrix();
    if (mirror) {
      matrix.preScale(-1.0f, 1.0f);
    }
    matrix.postRotate(angle);
    return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
  }

  private File thumbnailPathFromMediaId(String mediaId) {
    File thumbnailPath = null;
    String thumbnailName = mediaId + "_mthumb.png";
    File dir = new File(dataDir, "FFTemp");
    if (!dir.exists()) {
      if (!dir.mkdirs()) {
        Log.e("Mendr", "Failed to create storage directory.");
        return thumbnailPath;
      }
    }

    thumbnailPath = new File(dir.getPath() + File.separator + thumbnailName);

    return thumbnailPath;
  }
  PictureCallback jpegPictureCallback = new PictureCallback() {
    public void onPictureTaken(final byte[] data, Camera arg1) {
      Log.d(TAG, "CameraPreview jpegPictureCallback");
      try {
        new Thread() {
          public void run() {
            File pictureFile = thumbnailPathFromMediaId("12345");

            try {
              FileOutputStream fos = new FileOutputStream(pictureFile);
              fos.write(data);
              fos.close();
            } catch (Exception error) {
              Log.d(TAG, "File not saved: " + error.getMessage());
            }

            //Step 3. Get Exif Info from File path
            ExifInterface exif;
            JSONObject metadata = new JSONObject();
            String orient = "0";
            try {
              exif = new ExifInterface(pictureFile.getPath());

              JSONObject exifData = new JSONObject();
              exifData.put("ApertureValue", exif.getAttribute(ExifInterface.TAG_APERTURE_VALUE));
              exifData.put("FNumber", exif.getAttribute(ExifInterface.TAG_F_NUMBER));
              exifData.put("FocalLength", exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH));
              exifData.put("FocalLenIn35mmFilm", exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM));
              exifData.put("XResolution", exif.getAttribute(ExifInterface.TAG_X_RESOLUTION));
              exifData.put("YResolution", exif.getAttribute(ExifInterface.TAG_Y_RESOLUTION));
              exifData.put("Orientation", exif.getAttribute(ExifInterface.TAG_ORIENTATION));
              orient = exif.getAttribute(ExifInterface.TAG_ORIENTATION);
              if(orient.equals("0")){
                exifData.put("PixelYDimension", exif.getAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION));
                exifData.put("PixelXDimension", exif.getAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION));
              }else{
                exifData.put("PixelXDimension", exif.getAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION));
                exifData.put("PixelYDimension", exif.getAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION));
              }

              metadata.put("{Exif}", exifData);

              pictureFile.delete();
            } catch (Exception e) {
              e.printStackTrace();
            }

            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
            if(!orient.equals("0")){
              bitmap = rotateBitmap(bitmap, mPreview.getDisplayOrientation(), cameraCurrentlyLocked == Camera.CameraInfo.CAMERA_FACING_FRONT);
            }
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            bitmap.compress(CompressFormat.JPEG, currentQuality, outputStream);
            byte[] byteArray = outputStream.toByteArray();
            String encodedImage = Base64.encodeToString(byteArray, Base64.NO_WRAP);
            eventListener.onPictureTaken(encodedImage, metadata);
            Log.d(TAG, "CameraPreview pictureTakenHandler called back");
          }
        }.start();
      } catch (OutOfMemoryError e) {
        // most likely failed to allocate memory for rotateBitmap
        Log.d(TAG, "CameraPreview OutOfMemoryError");
        // failed to allocate memory
        eventListener.onPictureTakenError("Picture too large (memory)");
      } catch (Exception e) {
        Log.d(TAG, "CameraPreview onPictureTaken general exception");
      } finally {
        canTakePicture = true;
        mCamera.startPreview();
      }
    }
  };

  private Camera.Size getOptimalPictureSize(final int width, final int height, final Size previewSize, final List < Camera.Size > supportedSizes) {
        /*
          get the supportedPictureSize that:
          - matches exactly width and height
          - has the closest aspect ratio to the preview aspect ratio
          - has picture.width and picture.height closest to width and height
          - has the highest supported picture width and height up to 2 Megapixel if width == 0 || height == 0
        */
    Camera.Size size = mCamera.new Size(width, height);

    // convert to landscape if necessary
    if (size.width < size.height) {
      int temp = size.width;
      size.width = size.height;
      size.height = temp;
    }

    double previewAspectRatio = (double) previewSize.getWidth() / (double) previewSize.getHeight();

    if (previewAspectRatio < 1.0) {
      // reset ratio to landscape
      previewAspectRatio = 1.0 / previewAspectRatio;
    }

    Log.d(TAG, "CameraPreview previewAspectRatio " + previewAspectRatio);

    double aspectTolerance = 0.1;
    double bestDifference = Double.MAX_VALUE;

    for (int i = 0; i < supportedSizes.size(); i++) {
      Camera.Size supportedSize = supportedSizes.get(i);

      // Perfect match
      if (supportedSize.equals(size)) {
        Log.d(TAG, "CameraPreview optimalPictureSize " + supportedSize.width + 'x' + supportedSize.height);
        return supportedSize;
      }

      double difference = Math.abs(previewAspectRatio - ((double) supportedSize.width / (double) supportedSize.height));

      if (difference < bestDifference - aspectTolerance) {
        // better aspectRatio found
        if ((width != 0 && height != 0) || (supportedSize.width * supportedSize.height < 2048 * 1024)) {
          size.width = supportedSize.width;
          size.height = supportedSize.height;
          bestDifference = difference;
        }
      } else if (difference < bestDifference + aspectTolerance) {
        // same aspectRatio found (within tolerance)
        if (width == 0 || height == 0) {
          // set highest supported resolution below 2 Megapixel
          if ((size.width < supportedSize.width) && (supportedSize.width * supportedSize.height < 2048 * 1024)) {
            size.width = supportedSize.width;
            size.height = supportedSize.height;
          }
        } else {
          // check if this pictureSize closer to requested width and height
          if (Math.abs(width * height - supportedSize.width * supportedSize.height) < Math.abs(width * height - size.width * size.height)) {
            size.width = supportedSize.width;
            size.height = supportedSize.height;
          }
        }
      }
    }
    Log.d(TAG, "CameraPreview optimalPictureSize " + size.width + 'x' + size.height);
    return size;
  }

  public void takePicture(final int width, final int height, final int quality) {
    Log.d(TAG, "CameraPreview takePicture width: " + width + ", height: " + height + ", quality: " + quality);

    if (mPreview != null) {
      if (!canTakePicture) {
        return;
      }

      canTakePicture = false;

      Camera.Parameters params = mCamera.getParameters();

      // <<<<<<< HEAD
      //         Camera.Size size = getOptimalPictureSize(width, height, params.getPreviewSize(), params.getSupportedPictureSizes());
      //         params.setPictureSize(size.width, size.height);
      //         params.setJpegQuality(quality);
      // =======
//      Camera.Size size = getOptimalPictureSize(width, height, params.getPreviewSize(), params.getSupportedPictureSizes());

      Size customSize = new Size(1600, 900);

      Camera.Size size = getOptimalPictureSize(width, height, customSize, params.getSupportedPictureSizes());
      params.setPictureSize(size.width, size.height);
      currentQuality = quality;

      if (cameraCurrentlyLocked == Camera.CameraInfo.CAMERA_FACING_FRONT) {
        // The image will be recompressed in the callback
        params.setJpegQuality(99);
      } else {
        params.setJpegQuality(quality);
      }

      params.setRotation(mPreview.getDisplayOrientation());
      // >>>>>>> d2a671a65987b5d3a77b5407c8520227ef434d1f

      mCamera.setParameters(params);
      mCamera.takePicture(null, null, jpegPictureCallback);
    } else {
      canTakePicture = true;
    }
  }

  public void setFocusArea(final int pointX, final int pointY, final Camera.AutoFocusCallback callback) {
    if (mCamera != null) {

      mCamera.cancelAutoFocus();

      Camera.Parameters parameters = mCamera.getParameters();

      Rect focusRect = calculateTapArea(pointX, pointY, 1f);
      parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
      parameters.setFocusAreas(Arrays.asList(new Camera.Area(focusRect, 1000)));

      if (parameters.getMaxNumMeteringAreas() > 0) {
        Rect meteringRect = calculateTapArea(pointX, pointY, 1.5f);
        parameters.setMeteringAreas(Arrays.asList(new Camera.Area(meteringRect, 1000)));
      }

      try {
        setCameraParameters(parameters);
        mCamera.autoFocus(callback);
      } catch (Exception e) {
        Log.d(TAG, e.getMessage());
        callback.onAutoFocus(false, this.mCamera);
      }
    }
  }

  private Rect calculateTapArea(float x, float y, float coefficient) {
    return new Rect(
            Math.round((x - 100) * 2000 / width - 1000),
            Math.round((y - 100) * 2000 / height - 1000),
            Math.round((x + 100) * 2000 / width - 1000),
            Math.round((y + 100) * 2000 / height - 1000)
    );
  }
}
