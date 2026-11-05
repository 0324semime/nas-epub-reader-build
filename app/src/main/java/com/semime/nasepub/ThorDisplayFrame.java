package com.semime.nasepub;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/**
 * Rotates one Presentation's content without changing the primary display.
 * The child's layout dimensions are swapped BEFORE rotation. Otherwise the
 * second screen may be sideways or its EPUB page may be almost fully clipped.
 *
 * degrees: -90 (Thor in the observed orientation), 0, or +90.
 */
public final class ThorDisplayFrame extends FrameLayout {
    private final View child;
    private final int degrees;

    public ThorDisplayFrame(Context context,View content,int degrees){
        super(context);
        this.child=content;
        this.degrees=degrees==90?90:degrees==-90?-90:0;
        setClipChildren(false);
        setClipToPadding(false);
        addView(child,new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @Override protected void onMeasure(int widthSpec,int heightSpec){
        int width=View.MeasureSpec.getSize(widthSpec);
        int height=View.MeasureSpec.getSize(heightSpec);
        if(width<=0||height<=0){
            super.onMeasure(widthSpec,heightSpec);
            return;
        }
        int innerWidth=degrees==0?width:height;
        int innerHeight=degrees==0?height:width;
        child.measure(View.MeasureSpec.makeMeasureSpec(innerWidth,View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(innerHeight,View.MeasureSpec.EXACTLY));
        setMeasuredDimension(width,height);
    }

    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        int width=r-l,height=b-t;
        int innerWidth=degrees==0?width:height;
        int innerHeight=degrees==0?height:width;
        int left=(width-innerWidth)/2,top=(height-innerHeight)/2;
        child.layout(left,top,left+innerWidth,top+innerHeight);
        child.setPivotX(innerWidth/2f);
        child.setPivotY(innerHeight/2f);
        child.setRotation(degrees);
    }
}
