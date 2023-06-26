package it.lorenzogiorgi.tesi.utiliy;

import com.opencsv.CSVWriter;

import java.io.FileWriter;
import java.io.IOException;

public class TestUtility {

    public static void writeExperimentData(String experiment, String[] experimentData) {
        try (CSVWriter writer = new CSVWriter(new FileWriter("/home/ubuntu/experiments/"+experiment+".csv", true), CSVWriter.DEFAULT_SEPARATOR,
                CSVWriter.NO_QUOTE_CHARACTER, CSVWriter.DEFAULT_ESCAPE_CHARACTER, CSVWriter.DEFAULT_LINE_END)) {
            writer.writeNext(experimentData);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
