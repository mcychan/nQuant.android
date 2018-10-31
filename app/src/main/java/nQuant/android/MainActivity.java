package nQuant.android;

import android.app.ProgressDialog;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import com.android.nQuant.PnnLABQuantizer;
import com.android.nQuant.PnnQuantizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends AppCompatActivity {
    Button button;
    ImageView image;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        addListenerOnButton();
    }

    private class ConversionTask extends AsyncTask<Object,Void,Bitmap> {
        private final String filePath;
        private final ProgressDialog dialog;

        public ConversionTask(MainActivity activity, String filePath) {
            this.filePath = filePath;
            dialog = new ProgressDialog(activity);
        }

        @Override
        protected void onPreExecute() {
            setProgressBarIndeterminateVisibility(true);
            dialog.setMessage("Converting...");
            dialog.show();
        }

        @Override
        protected void onPostExecute(Bitmap result) {
            setProgressBarIndeterminateVisibility(false);
            image.setImageBitmap(result);
            image.getLayoutParams().width = getResources().getDisplayMetrics().widthPixels;
            image.getLayoutParams().height = (image.getLayoutParams().width * result.getHeight()) / result.getWidth();
            image.setScaleType(ImageView.ScaleType.FIT_XY);
            button.setText("Quit");
            button.setEnabled(true);

            if (dialog.isShowing())
                dialog.dismiss();
        }

        @Override
        protected Bitmap doInBackground(Object... params) {
            try {
                PnnQuantizer pnnQuantizer = new PnnLABQuantizer(filePath);
                return pnnQuantizer.convert(256, true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            } catch (Exception e) {
                throw e;
            }
        }
    }

    public void addListenerOnButton() {
        image = (ImageView) findViewById(R.id.imageView1);

        button = (Button) findViewById(R.id.btnChangeImage);
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

                    File file = new File(getCacheDir(), "sample.jpg");
                    if (!file.exists()) {
                        InputStream asset = getResources().openRawResource(+ R.drawable.sample);
                        FileOutputStream output = new FileOutputStream(file);
                        final byte[] buffer = new byte[1024];
                        int size;
                        while ((size = asset.read(buffer)) != -1) {
                            output.write(buffer, 0, size);
                        }
                        asset.close();
                        output.close();
                    }

                    new ConversionTask(MainActivity.this, file.getAbsolutePath()).execute();

                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(MainActivity.this, "Error! " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });

    }
}