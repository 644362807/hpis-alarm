package com.hpis.alarm.dto;

public class AlarmVideo {
   private String videoPath ;
   private  String videoPicture ;

    public String getVideoPath() {
        return videoPath;
    }

    public void setVideoPath(String videoPath) {
        this.videoPath = videoPath;
    }

    public String getVideoPicture() {
        return videoPicture;
    }

    public void setVideoPicture(String videoPicture) {
        this.videoPicture = videoPicture;
    }

    @Override
    public String toString() {
        return "AlarmVideo{" +
                "videoPath='" + videoPath + '\'' +
                ", videoPicture='" + videoPicture + '\'' +
                '}';
    }
}
