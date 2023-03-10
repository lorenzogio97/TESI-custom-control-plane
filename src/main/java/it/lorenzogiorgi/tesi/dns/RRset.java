package it.lorenzogiorgi.tesi.dns;

import java.util.List;

/**
 * Object that represent a PowerDNS RRSet
 */
public class RRset {
    private String name;
    private String type;
    private int ttl;
    private String changetype;
    private List<Record> records;
    private List<Comment> comments;

    public RRset(String name, String type, int ttl, String changetype, List<Record> records, List<Comment> comments) {
        this.name = name;
        this.type = type;
        this.ttl = ttl;
        this.changetype = changetype;
        this.records = records;
        this.comments=comments;
    }

    public RRset(String name, String type, int ttl, String changetype, List<Record> records) {
        this(name,type, ttl, changetype, records,null);
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

    public List<Comment> getComments() {
        return comments;
    }

    public void setComments(List<Comment> comments) {
        this.comments = comments;
    }
}
