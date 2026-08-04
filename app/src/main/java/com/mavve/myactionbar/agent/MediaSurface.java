package com.mavve.myactionbar.agent;

/**
 * The in-app screen the agent can put media on — images, video, web pages —
 * so the user never has to leave the app. Implemented by the activity;
 * called from agent tool executions on background threads (implementations
 * must hop to the main thread themselves).
 */
public interface MediaSurface {

    void showImage(String url, String caption);

    void playVideo(String url, String caption);

    void showPage(String url);

    void hideMedia();
}
