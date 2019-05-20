package com.example.mayan.hardwaresensnor;

import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private SensorManager sensorManager;
    private LineGraphSeries<DataPoint> series;
    private static double currentX;
    private ThreadPoolExecutor liveChartExecutor;
    private LinkedBlockingQueue<Double> accelerationQueue = new LinkedBlockingQueue<>(10);
    private float x, y, z;
    private long timenow = 0, timeprev = 0, timestamp =0 ;
    TextView tv1,tv2,tv3;
    Thread myCommThread=null;
    private CommThread commThread=null;
    PrintWriter out;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        tv1=(TextView) findViewById(R.id.textView1);
        tv2=(TextView) findViewById(R.id.textView2);
        tv3=(TextView) findViewById(R.id.textView3);

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);

        GraphView graph = (GraphView) findViewById(R.id.graph);

        series = new LineGraphSeries<>();
        series.setColor(Color.GREEN);
        graph.addSeries(series);

        // activate horizontal zooming and scrolling
        graph.getViewport().setScalable(true);

        // activate horizontal scrolling
        graph.getViewport().setScrollable(true);

        // activate horizontal and vertical zooming and scrolling
        graph.getViewport().setScalableY(true);

        // activate vertical scrolling
        graph.getViewport().setScrollableY(true);
        // To set a fixed manual viewport use this:
        // set manual X bounds
        graph.getViewport().setXAxisBoundsManual(true);
        graph.getViewport().setMinX(0.5);
        graph.getViewport().setMaxX(6.5);

        // set manual Y bounds
        graph.getViewport().setYAxisBoundsManual(true);
        graph.getViewport().setMinY(0);
        graph.getViewport().setMaxY(10);

        currentX = 0;

        // Start chart thread
        liveChartExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(1);
        if (liveChartExecutor != null)
            liveChartExecutor.execute(new AccelerationChart(new AccelerationChartHandler()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        sensorManager.registerListener(this,
                sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                SensorManager.SENSOR_DELAY_NORMAL);
        this.commThread= new CommThread();
        this.myCommThread=new Thread(this.commThread);
        this.myCommThread.start();
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent) {

        if (sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            getAccelerometer(sensorEvent);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {

    }

    @Override
    protected void onPause() {
        // unregister listener
        super.onPause();
        sensorManager.unregisterListener(this);
        if(commThread!=null){
            commThread.stopComm();
        }
    }

    private void getAccelerometer(SensorEvent event) {
        float[] values = event.values;
        // Movement
        x = values[0];
        y = values[1];
        z = values[2];

        timenow=event.timestamp;
        timestamp=(timenow-timeprev)/1000000;
        refreshDisplay();

        double accelerationSquareRoot = (x * x + y * y + z * z) / (SensorManager.GRAVITY_EARTH * SensorManager.GRAVITY_EARTH);
        double acceleration = Math.sqrt(accelerationSquareRoot);

        accelerationQueue.offer(acceleration);
    }

    private void refreshDisplay() {
        //String output=String.format("x: "+x+" | y: "+y+" | z: "+z);
        tv1.setText(String.format("%.2f", x));
        tv2.setText(String.format("%.2f", y));
        tv3.setText(String.format("%.2f", z));
    }

    private class AccelerationChartHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            Double accelerationY = 0.0D;
            if (!msg.getData().getString("ACCELERATION_VALUE").equals(null) && !msg.getData().getString("ACCELERATION_VALUE").equals("null")) {
                accelerationY = (Double.parseDouble(msg.getData().getString("ACCELERATION_VALUE")));
            }

            series.appendData(new DataPoint(currentX, accelerationY), true, 10);
            currentX = currentX + 1;
        }
    }   

    private class AccelerationChart implements Runnable {
        private boolean drawChart = true;
        private Handler handler;

        public AccelerationChart(Handler handler) {
            this.handler = handler;
        }

        @Override
        public void run() {
            while (drawChart) {
                Double accelerationY;
                try {
                    Thread.sleep(300); // Speed up the X axis
                    accelerationY = accelerationQueue.poll();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    continue;
                }
                if (accelerationY == null)
                    continue;

                // currentX value will be excced the limit of double type range
                // To overcome this problem comment of this line
                // currentX = (System.currentTimeMillis() / 1000) * 8 + 0.6;

                Message msgObj = handler.obtainMessage();
                Bundle b = new Bundle();
                b.putString("ACCELERATION_VALUE", String.valueOf(accelerationY));
                msgObj.setData(b);
                handler.sendMessage(msgObj);
            }
        }
    }

    class CommThread implements Runnable{
        private volatile boolean stopFlag=false;
        private ServerSocket ss=null;
        private static final int SERVERPORT=6000;

        public void run(){
            Socket s=null;

            try {
                ss=new ServerSocket(SERVERPORT);
            } catch (IOException e) {
                e.printStackTrace();
            }

            try {
                s=ss.accept();
            } catch (IOException e) {
                e.printStackTrace();
            }

            while (!stopFlag){
                try {
                    out=new PrintWriter(new BufferedWriter(new OutputStreamWriter(s.getOutputStream())),true);
                    out.printf("X: %3.2f | Y: %3.2f | Z: %3.2f \n",x,y,z);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        public void stopComm(){
            this.stopFlag=true;
            if (ss!=null){
                try {
                    ss.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}