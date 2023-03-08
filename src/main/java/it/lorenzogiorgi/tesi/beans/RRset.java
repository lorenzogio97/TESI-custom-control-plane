package it.lorenzogiorgi.tesi.beans;

import java.util.List;

public class RRset {
    String name;
    String type;
    int ttl;
    String changetype;
    List<Record> records;

    public RRset(String name, String type, int ttl, String changetype, List<Record> records) {
        this.name = name;
        this.type = type;
        this.ttl = ttl;
        this.changetype = changetype;
        this.records = records;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getTtl() {
        return ttl;
    }

    public void setTtl(int ttl) {
        this.ttl = ttl;
    }

    public String getChangetype() {
        return changetype;
    }

    public void setChangetype(String changetype) {
        this.changetype = changetype;
    }

    public List<Record> getRecords() {
        return records;
    }

    public void setRecords(List<Record> records) {
        this.records = records;
    }
}
