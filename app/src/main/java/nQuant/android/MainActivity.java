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
        @Override
        protected Bitmap doInBackground(Object... params) {
            try {
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

                PnnQuantizer pnnQuantizer = new PnnQuantizer(file.getAbsolutePath());
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
                ProgressDialog dialog = null;
                try {
                    setProgressBarIndeterminateVisibility(true);
                    dialog = ProgressDialog.show(MainActivity.this, "nQuant.android",
                            "Converting...", true, false);
                    Bitmap bitmap = new ConversionTask().execute().get();
                    image.getLayoutParams().width = getResources().getDisplayMetrics().widthPixels;
                    image.getLayoutParams().height = (image.getLayoutParams().width * bitmap.getHeight()) / bitmap.getWidth();
                    image.setScaleType(ImageView.ScaleType.FIT_XY);
                    image.setImageBitmap(bitmap);
                    button.setEnabled(false);
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(MainActivity.this, "Error! " + e.getMessage(), Toast.LENGTH_SHORT).show();
                } finally {
                    setProgressBarIndeterminateVisibility(false);
                    if (dialog != null && dialog.isShowing())
                        dialog.dismiss();
                }
            }
        });

    }
}
