package org.example.listener;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import org.example.notification.GmailAutoReplyService;
import java.util.concurrent.*;
import java.util.logging.Logger;

@WebListener
public class GmailAutoReplyListener implements ServletContextListener {
    private static final Logger LOG=Logger.getLogger(GmailAutoReplyListener.class.getName());
    private ScheduledExecutorService worker;
    @Override public void contextInitialized(ServletContextEvent event) {
        worker=Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"gmail-auto-replies");t.setDaemon(true);return t;});
        worker.scheduleWithFixedDelay(()->{
            try { GmailAutoReplyService.poll(); }
            catch(Exception e) { LOG.warning("Gmail automatic replies unavailable: " + e.getClass().getSimpleName()); }
        },15,30,TimeUnit.SECONDS);
    }
    @Override public void contextDestroyed(ServletContextEvent event) {
        if(worker!=null)worker.shutdownNow();
    }
}
