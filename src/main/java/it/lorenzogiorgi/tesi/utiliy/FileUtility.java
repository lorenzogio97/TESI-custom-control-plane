package it.lorenzogiorgi.tesi.utiliy;

import it.lorenzogiorgi.tesi.Orchestrator;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class FileUtility {
    private static final Logger logger = LogManager.getLogger(Orchestrator.class.getName());

    public static String readTextFile(String filePath) {
        String data = "";
        try {
            data = new String(
                    Files.readAllBytes(Paths.get(filePath)));
        } catch (IOException e) {
            logger.error("File "+filePath+ " not read due to IOException");
            throw new RuntimeException(e);
        }
        return data;
    }
}
