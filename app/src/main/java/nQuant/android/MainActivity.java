package nQuant.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;

import com.android.nQuant.PnnLABQuantizer;
import com.android.nQuant.PnnQuantizer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity {
    Button button;
    ImageView image;
    String filePath;

    ActivityResultLauncher<Intent> activityResultLauncher = registerForActivityResult(
    new ActivityResultContracts.StartActivityForResult(),
    new ActivityResultCallback<ActivityResult>() {
        @Override
        public void onActivityResult(ActivityResult result) {
            if (result.getResultCode() == Activity.RESULT_OK) {
                Intent data = result.getData();
                if (null != data) {
                    filePath = data.getDataString();
                    image.setImageURI(Uri.parse(filePath));
                }
            }
        }
    });

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        File file = new File(getCacheDir(), "sample.jpg");
        if (file.exists())
            file.delete();

        filePath = file.getAbsolutePath();
        InputStream asset = getResources().openRawResource(+R.drawable.sample);
         try(FileOutputStream output = new FileOutputStream(file)) {
            final byte[] buffer = new byte[1024];
            int size;
            while ((size = asset.read(buffer)) != -1) {
                output.write(buffer, 0, size);
            }
            asset.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        image = findViewById(R.id.imageView1);
        image.setClickable(true);
        image.setOnClickListener(arg0 -> {
            if("Quit".equals(button.getText()))
                return;

            try {
                Intent intent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                intent.setType("image/*");
                activityResultLauncher.launch(Intent.createChooser(intent, "Please select Image"));
            } catch (android.content.ActivityNotFoundException ex) {
                // Potentially direct the user to the Market with a Dialog
                Toast.makeText(getApplicationContext(), "Please install a File Manager.", Toast.LENGTH_SHORT).show();
            }
        });
        addListenerOnButton();
    }

    public AlertDialog createProgressDialog() {
        int llPadding = 30;
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.HORIZONTAL);
        ll.setPadding(llPadding, llPadding, llPadding, llPadding);
        ll.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams llParam = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        llParam.gravity = Gravity.CENTER;
        ll.setLayoutParams(llParam);

        ProgressBar progressBar = new ProgressBar(this);
        progressBar.setIndeterminate(true);
        progressBar.setPadding(0, 0, llPadding, 0);
        progressBar.setLayoutParams(llParam);

        llParam = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        llParam.gravity = Gravity.CENTER;
        llParam.leftMargin = 5;
        TextView tvText = new TextView(this);
        tvText.setText("Converting ...");
        tvText.setTextSize(20);
        tvText.setLayoutParams(llParam);

        ll.addView(progressBar);
        ll.addView(tvText);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(false);
        builder.setView(ll);

        AlertDialog dialog = builder.create();
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            WindowManager.LayoutParams layoutParams = new WindowManager.LayoutParams();
            layoutParams.copyFrom(dialog.getWindow().getAttributes());
            layoutParams.width = LinearLayout.LayoutParams.WRAP_CONTENT;
            layoutParams.height = LinearLayout.LayoutParams.WRAP_CONTENT;
            dialog.getWindow().setAttributes(layoutParams);
        }
        return dialog;
    }

    public void addListenerOnButton() {
        button = findViewById(R.id.btnChangeImage);
        button.setTransformationMethod(null);
        button.setOnClickListener(arg0 -> {
            if("Quit".equals(button.getText())) {
                MainActivity.this.finish();
                System.exit(0);
                return;
            }

            try {
                button.setEnabled(false);

                final AlertDialog dialog = createProgressDialog();
                final Handler handler = new Handler(getMainLooper());

                final ExecutorService executor = Executors.newSingleThreadExecutor();
                executor.execute(() -> {
                    try {
                        PnnQuantizer pnnQuantizer = new PnnQuantizer(filePath);
                        final Bitmap result = pnnQuantizer.convert(256, true);

                        handler.post(() -> {
                            image.setImageBitmap(result);
                            DisplayMetrics metrics = new DisplayMetrics();
                            getWindowManager().getDefaultDisplay().getMetrics(metrics);
                            if(metrics.widthPixels < metrics.heightPixels)
                                image.getLayoutParams().height = result.getHeight() * (metrics.widthPixels / result.getWidth());
                            else
                                image.getLayoutParams().width = result.getWidth() * (metrics.heightPixels / result.getHeight());

                            image.setScaleType(ImageView.ScaleType.FIT_XY);
                            button.setText("Quit");
                            button.setEnabled(true);

                            if(dialog.isShowing())
                                dialog.dismiss();
                        });
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            } catch (Throwable t) {
                t.printStackTrace();
                Toast.makeText(MainActivity.this, "Error! " + t.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });

    }
}