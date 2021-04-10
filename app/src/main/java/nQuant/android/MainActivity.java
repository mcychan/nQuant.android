package nQuant.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.android.nQuant.PnnLABQuantizer;
import com.android.nQuant.PnnQuantizer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    Button button;
    ImageView image;
    String filePath;

    final static int IMPORT_PICTURE = 10001;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == IMPORT_PICTURE && resultCode == RESULT_OK && null != data) {
            filePath = data.getDataString();
            image.setImageURI(Uri.parse(filePath));
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        File file = new File(getCacheDir(), "sample.jpg");
        if (file.exists())
            file.delete();

        filePath = file.getAbsolutePath();
        InputStream asset = getResources().openRawResource(+R.drawable.sample);
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(file);
            final byte[] buffer = new byte[1024];
            int size;
            while ((size = asset.read(buffer)) != -1) {
                output.write(buffer, 0, size);
            }
            asset.close();
            output.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        image = findViewById(R.id.imageView1);
        image.setClickable(true);
        image.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View arg0) {
                if("Quit".equals(button.getText()))
                    return;

                try {
                    Intent intent = new Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
                    intent.setType("image/*");
                    startActivityForResult(Intent.createChooser(intent, "Please select Image"), IMPORT_PICTURE);
                } catch (android.content.ActivityNotFoundException ex) {
                    // Potentially direct the user to the Market with a Dialog
                    Toast.makeText(getApplicationContext(), "Please install a File Manager.", Toast.LENGTH_SHORT).show();
                }
            }
        });
        addListenerOnButton();
    }

    public ProgressBar createProgressBar() {
        LinearLayout layout = findViewById(R.id.display);
        ProgressBar progressBar = progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
        layout.addView(progressBar);

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        progressBar.setVisibility(View.VISIBLE);
        return progressBar;
    }

    public void addListenerOnButton() {
        button = findViewById(R.id.btnChangeImage);
        button.setTransformationMethod(null);
        button.setOnClickListener(new OnClickListener() {

            @Override
            public void onClick(View arg0) {
                if("Quit".equals(button.getText())) {
                    MainActivity.this.finish();
                    System.exit(0);
                    return;
                }

                try {
                    button.setEnabled(false);

                    final ProgressBar progressBar = createProgressBar();
                    final Handler handler = new Handler(getMainLooper());

                    final ExecutorService executor = Executors.newSingleThreadExecutor();
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                PnnQuantizer pnnQuantizer = new PnnQuantizer(filePath);
                                final Bitmap result = pnnQuantizer.convert(256, true);

                                handler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        image.setImageBitmap(result);
                                        image.getLayoutParams().width = getResources().getDisplayMetrics().widthPixels;
                                        image.getLayoutParams().height = (image.getLayoutParams().width * result.getHeight()) / result.getWidth();
                                        image.setScaleType(ImageView.ScaleType.FIT_XY);
                                        button.setText("Quit");
                                        button.setEnabled(true);

                                        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
                                        progressBar.setVisibility(View.GONE);
                                    }
                                });
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            } catch (Exception e) {
                                throw e;
                            }
                        }
                    });
                } catch (Throwable t) {
                    t.printStackTrace();
                    Toast.makeText(MainActivity.this, "Error! " + t.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });

    }
}