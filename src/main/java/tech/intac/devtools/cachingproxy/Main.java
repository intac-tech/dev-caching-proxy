package tech.intac.devtools.cachingproxy;

import java.util.logging.Logger;

import javax.swing.*;

public class Main {

    private static final Logger log = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) throws Exception {
        log.info("Starting the proxy server");
        var server = new JettyServer();
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                log.info("Stopping proxy server gracefully.");
                server.stop();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }));

        try {
            UIManager.setLookAndFeel("com.formdev.flatlaf.FlatLightLaf");
        } catch (Exception ex) {
            // this should not happen but just in case...
        }

        new AppUI();
    }
}
