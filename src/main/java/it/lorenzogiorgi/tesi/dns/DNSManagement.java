package it.lorenzogiorgi.tesi.dns;

import com.google.gson.Gson;
import it.lorenzogiorgi.tesi.configuration.Configuration;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;



public class DNSManagement {
    private static final OkHttpClient client;
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    static {
        client= new OkHttpClient();
    }

    public static boolean updateDNSRecord(String zoneName, String domainName, String type, int ttl, String content) {
        return updateDNSRecord(zoneName, domainName, type, ttl, "REPLACE", content, false);
    }

    public static boolean updateDNSRecord(String zoneName, String domainName, String type, int ttl,
                                          String changeType, String content, boolean disabled) {
        Gson gson= new Gson();
        Record record = new Record(content, disabled);
        List<Record> recordList = new ArrayList<>();
        recordList.add(record);
        RRset rRset = new RRset(domainName+".",type, ttl,changeType, recordList);
        List<RRset> rRsetList = new ArrayList<>();
        rRsetList.add(rRset);
        Zone zone= new Zone();
        zone.setRrsets(rRsetList);
        String body_string = gson.toJson(zone);
        RequestBody body = RequestBody.create(JSON, body_string);
        Request request = new Request.Builder()
                .url("http://"+ Configuration.DNS_API_IP+":"+Configuration.DNS_API_PORT+
                        "/api/v1/servers/"+Configuration.DNS_API_SERVER_ID+"/zones/"+zoneName)
                .header("X-API-Key", Configuration.DNS_API_KEY)
                .patch(body)
                .build();

        try (Response response = client.newCall(request).execute()) {
            return response.code() == 204;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

    }

    public static boolean deleteDNSRecord(String zoneName, String domainName, String type) {
        return updateDNSRecord(zoneName,domainName, type, 0, "DELETE", null, false);
    }


}
