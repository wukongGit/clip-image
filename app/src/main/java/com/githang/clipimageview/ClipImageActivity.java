package com.githang.clipimageview;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.os.AsyncTaskCompat;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.githang.clipimage.ClipImageView;
import com.githang.clipimage.Viewfinder;

import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 裁剪图片
 *
 * @author Geek_Soledad (msdx.android@qq.com)
 * @version 2015-12-30 3.3
 * @since 2015-12-30 3.3
 */
public class ClipImageActivity extends Activity implements View.OnClickListener {

    private ClipImageView mClipImageView;
    private Viewfinder mCropBorder;
    private TextView mCancel;
    private TextView mClip;

    private String mOutput;
    private String mInputPath;
    private int mMaxWidth;

    // 图片被旋转的角度
    private int mDegree;
    // 大图被设置之前的缩放比例
    private int mSampleSize;
    private int mSourceWidth;
    private int mSourceHeight;

    private ProgressDialog mDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.setContentView(R.layout.activity_clip_image);
        mClipImageView = (ClipImageView) findViewById(R.id.clip_image_view);
        mCropBorder = (Viewfinder) findViewById(R.id.crop_border);
        mCancel = (TextView) findViewById(R.id.cancel);
        mClip = (TextView) findViewById(R.id.clip);

        mCancel.setOnClickListener(this);
        mClip.setOnClickListener(this);

        final Intent data = getIntent();
        mOutput = PhotoActionHelper.getOutputPath(data);
        mInputPath = PhotoActionHelper.getInputPath(data);
        mMaxWidth = PhotoActionHelper.getMaxOutputWidth(data);

        setImageAndClipParams();

        mDialog = new ProgressDialog(this);
        mDialog.setMessage(getString(R.string.msg_clipping_image));
    }

    private void setImageAndClipParams() {
        mClipImageView.post(new Runnable() {
            @Override
            public void run() {
                final Rect border = mCropBorder.getBorder();
                mClipImageView.setClipBorder(border);
                mClipImageView.setMaxOutputWidth(mMaxWidth);

                mDegree = readPictureDegree(mInputPath);

                final boolean isRotate = (mDegree == 90 || mDegree == 270);

                final BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(mInputPath, options);

                mSourceWidth = options.outWidth;
                mSourceHeight = options.outHeight;

                // 如果图片被旋转，则宽高度置换
                int w = isRotate ? options.outHeight : options.outWidth;

                mSampleSize = 1;
                // 裁剪是宽高比例3:2，只考虑宽度情况，这里按border宽度的两倍来计算缩放。
                // sampleSize的值为2的幂次方，如果设为其他值，会降为比它小的最近的2的幂次方。
                for (int outW = w / 2, borderW = border.width(); outW > borderW; outW /= 2) {
                    mSampleSize *= 2;
                }

                options.inJustDecodeBounds = false;
                options.inSampleSize = mSampleSize;
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                final Bitmap source = BitmapFactory.decodeFile(mInputPath, options);

                // 解决图片被旋转的问题
                Bitmap target;
                if (mDegree == 0) {
                    target = source;
                } else {
                    final Matrix matrix = new Matrix();
                    matrix.postRotate(mDegree);
                    target = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, false);
                    if (target != source && !source.isRecycled()) {
                        source.recycle();
                    }
                }
                mClipImageView.setImageBitmap(target);
            }
        });
    }

    /**
     * 读取图片属性：旋转的角度
     *
     * @param path 图片绝对路径
     * @return degree旋转的角度
     */
    public static int readPictureDegree(String path) {
        int degree = 0;
        try {
            ExifInterface exifInterface = new ExifInterface(path);
            int orientation = exifInterface.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    degree = 90;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    degree = 180;
                    break;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    degree = 270;
                    break;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return degree;
    }

    @Override
    public void onClick(View v) {
        final int id = v.getId();
        switch (id) {
            case R.id.cancel:
                onBackPressed();
                break;
            case R.id.clip:
                clipImage();
                break;
            default:// do nothing
        }
    }

    private void clipImage() {
        if (mOutput != null) {
            mDialog.show();
            AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
                @Override
                protected Void doInBackground(Void... params) {
                    FileOutputStream fos = null;
                    try {
                        fos = new FileOutputStream(mOutput);
                        Bitmap bitmap = getClippedBitmap();
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);
                        if (!bitmap.isRecycled()) {
                            bitmap.recycle();
                        }
                        setResult(Activity.RESULT_OK, getIntent());
                    } catch (Exception e) {
                        Toast.makeText(ClipImageActivity.this, R.string.msg_could_not_save_photo, Toast.LENGTH_SHORT).show();
                    } finally {
                        if (fos != null) {
                            try {
                                fos.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }
                    return null;
                }

                @Override
                protected void onPostExecute(Void aVoid) {
                    mDialog.dismiss();
                    finish();
                }
            };
            AsyncTaskCompat.executeParallel(task);
        } else {
            finish();
        }
    }

    private Bitmap getClippedBitmap() {
        if (mSampleSize <= 1) {
            return mClipImageView.clip();
        }

        // 获取缩放位移后的矩阵值
        final float[] matrixValues = mClipImageView.getClipMatrixValues();
        final float scale = matrixValues[Matrix.MSCALE_X];
        final float transX = matrixValues[Matrix.MTRANS_X];
        final float transY = matrixValues[Matrix.MTRANS_Y];

        // 获取在显示的图片中裁剪的位置
        final Rect border = mCropBorder.getBorder();
        final float cropX = ((-transX + border.left) / scale) * mSampleSize;
        final float cropY = ((-transY + border.top) / scale) * mSampleSize;
        final float cropWidth = (border.width() / scale) * mSampleSize;
        final float cropHeight = (border.height() / scale) * mSampleSize;

        // 获取在旋转之前的裁剪位置
        final RectF srcRect = new RectF(cropX, cropY, cropX + cropWidth, cropY + cropHeight);
        final Rect clipRect = getRealRect(srcRect);

        final BitmapFactory.Options ops = new BitmapFactory.Options();
        final Matrix outputMatrix = new Matrix();

        outputMatrix.setRotate(mDegree);
        // 如果裁剪之后的图片宽高仍然太大,则进行缩小
        if (mMaxWidth > 0 && cropWidth > mMaxWidth) {
            final int sampleSize = cropWidth > mMaxWidth ? ((int) cropWidth / mMaxWidth) : 1;
            ops.inSampleSize = sampleSize;

            final float outputScale = mMaxWidth / (cropWidth / sampleSize);
            outputMatrix.postScale(outputScale, outputScale);
        }

        // 裁剪
        BitmapRegionDecoder decoder = null;
        try {
            decoder = BitmapRegionDecoder.newInstance(mInputPath, false);
            final Bitmap source = decoder.decodeRegion(clipRect, ops);
            recycleImageViewBitmap();
            return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), outputMatrix, false);
        } catch (Exception e) {
            return mClipImageView.clip();
        } finally {
            if (decoder != null && !decoder.isRecycled()) {
                decoder.recycle();
            }
        }
    }

    private Rect getRealRect(RectF srcRect) {
        final int reverseDegree = 360 - mDegree;// 矩形应该旋转的角度与图片需要旋转的角度方向相反
        switch (reverseDegree) {
            case 90:
                return new Rect((int) (mSourceWidth - srcRect.bottom), (int) srcRect.left,
                        (int) (mSourceWidth - srcRect.top), (int) srcRect.right);
            case 180:
                return new Rect((int) (mSourceHeight - srcRect.right), (int) (mSourceWidth - srcRect.bottom),
                        (int) (mSourceHeight - srcRect.left), (int) (mSourceWidth - srcRect.top));
            case 270:
                return new Rect((int) srcRect.top, (int) (mSourceHeight - srcRect.right),
                        (int) srcRect.bottom, (int) (mSourceHeight - srcRect.left));
            default:
                return new Rect((int) srcRect.left, (int) srcRect.top, (int) srcRect.right, (int) srcRect.bottom);
        }
    }

    private void recycleImageViewBitmap() {
        mClipImageView.post(new Runnable() {
            @Override
            public void run() {
                mClipImageView.setImageBitmap(null);
            }
        });
    }

    @Override
    public void onBackPressed() {
        setResult(Activity.RESULT_CANCELED, getIntent());
        super.onBackPressed();
    }
}