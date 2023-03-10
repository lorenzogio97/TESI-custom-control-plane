package it.lorenzogiorgi.tesi.dns;

import java.util.List;

/**
 * Object that represent a PowerDNS Zone
 */
public class Zone {
    private String id;
    private String name;
    private String type;
    private String url;
    private String kind;
    private List<RRset> rrsets;
    private int serial;
    private int notified_serial;
    private int edited_serial;
    private List<String> masters;
    private boolean dnssec;
    private String nsec3param;
    private boolean nsec3narrow;
    private boolean presigned;
    private String soa_edit;
    private String soa_edit_api;
    private boolean api_rectify;
    private String zone;
    private String catalog;
    private String account;
    private List<String> nameservers;
    private  List<String> master_tsig_key_ids;
    private  List<String> slave_tsig_key_ids;

    public Zone() {
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public List<RRset> getRrsets() {
        return rrsets;
    }

    public void setRrsets(List<RRset> rrsets) {
        this.rrsets = rrsets;
    }

    public int getSerial() {
        return serial;
    }

    public void setSerial(int serial) {
        this.serial = serial;
    }

    public int getNotified_serial() {
        return notified_serial;
    }

    public void setNotified_serial(int notified_serial) {
        this.notified_serial = notified_serial;
    }

    public int getEdited_serial() {
        return edited_serial;
    }

    public void setEdited_serial(int edited_serial) {
        this.edited_serial = edited_serial;
    }

    public List<String> getMasters() {
        return masters;
    }

    public void setMasters(List<String> masters) {
        this.masters = masters;
    }

    public boolean isDnssec() {
        return dnssec;
    }

    public void setDnssec(boolean dnssec) {
        this.dnssec = dnssec;
    }

    public String getNsec3param() {
        return nsec3param;
    }

    public void setNsec3param(String nsec3param) {
        this.nsec3param = nsec3param;
    }

    public boolean isNsec3narrow() {
        return nsec3narrow;
    }

    public void setNsec3narrow(boolean nsec3narrow) {
        this.nsec3narrow = nsec3narrow;
    }

    public boolean isPresigned() {
        return presigned;
    }

    public void setPresigned(boolean presigned) {
        this.presigned = presigned;
    }

    public String getSoa_edit() {
        return soa_edit;
    }

    public void setSoa_edit(String soa_edit) {
        this.soa_edit = soa_edit;
    }

    public String getSoa_edit_api() {
        return soa_edit_api;
    }

    public void setSoa_edit_api(String soa_edit_api) {
        this.soa_edit_api = soa_edit_api;
    }

    public boolean isApi_rectify() {
        return api_rectify;
    }

    public void setApi_rectify(boolean api_rectify) {
        this.api_rectify = api_rectify;
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public String getCatalog() {
        return catalog;
    }

    public void setCatalog(String catalog) {
        this.catalog = catalog;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public List<String> getNameservers() {
        return nameservers;
    }

    public void setNameservers(List<String> nameservers) {
        this.nameservers = nameservers;
    }

    public List<String> getMaster_tsig_key_ids() {
        return master_tsig_key_ids;
    }

    public void setMaster_tsig_key_ids(List<String> master_tsig_key_ids) {
        this.master_tsig_key_ids = master_tsig_key_ids;
    }

    public List<String> getSlave_tsig_key_ids() {
        return slave_tsig_key_ids;
    }

    public void setSlave_tsig_key_ids(List<String> slave_tsig_key_ids) {
        this.slave_tsig_key_ids = slave_tsig_key_ids;
    }
}
