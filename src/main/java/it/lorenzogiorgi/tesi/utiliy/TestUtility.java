package it.lorenzogiorgi.tesi.utiliy;

import com.google.gson.Gson;
import com.opencsv.CSVWriter;
import it.lorenzogiorgi.tesi.api.LoginRequest;
import it.lorenzogiorgi.tesi.configuration.ComputeNode;
import it.lorenzogiorgi.tesi.configuration.Configuration;
import okhttp3.*;

import java.io.FileWriter;
import java.io.IOException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import spark.Request;
import spark.Response;

public class TestUtility {

    protected final static Logger logger = LogManager.getLogger(TestUtility.class.getName());
    public static Long tSnapshotUpdate = null;


    public static void writeExperimentData(String experiment, String[] experimentData) {
        try (CSVWriter writer = new CSVWriter(new FileWriter("/home/ubuntu/experiments/"+experiment+".csv", true), CSVWriter.DEFAULT_SEPARATOR,
                CSVWriter.NO_QUOTE_CHARACTER, CSVWriter.DEFAULT_ESCAPE_CHARACTER, CSVWriter.DEFAULT_LINE_END)) {
            writer.writeNext(experimentData);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void sendUpdateTimestamp(long timestamp) {
        final OkHttpClient client = new OkHttpClient();;
        final MediaType TEXT = MediaType.parse("application/text; charset=utf-8");
        RequestBody body = RequestBody.create(String.valueOf(timestamp),TEXT);
        okhttp3.Request request = new okhttp3.Request.Builder()
                .url("https://orchestrator.lorenzogiorgi.com/experiement/tprop")
                .post(body)
                .build();

        try (okhttp3.Response response = client.newCall(request).execute()) {
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static String tpropExperiment(Request request, Response response) {
        Gson gson = new Gson();
        String requestBody = request.body();
        TPropPayload tPropPayload = gson.fromJson(requestBody, TPropPayload.class);

        long tProp = Long.parseLong(tPropPayload.getT_prop());
        long tSetup = Long.parseLong(tPropPayload.getT_setup());

        logger.trace("snapshot "+ tSnapshotUpdate);
        logger.trace("tProp "+ tProp);
        logger.trace("tSetup  "+ tSetup);

        int deltaProp = (int)((int)tProp-tSnapshotUpdate);
        int deltaSetup = (int)((int)tSetup-tProp);

        TestUtility.writeExperimentData("tprop-setup-1000user", new String[]{String.valueOf(deltaProp),
                String.valueOf(deltaSetup)});

        response.status(200);
        return "";
    }

}
