package it.lorenzogiorgi.tesi.dns;

/**
 * Object that represent a PowerDNS comment
 */
public class Comment {
    private String content;
    private String account;
    private int modified_at;

    public Comment(String content, String account, int modified_at) {
        this.content = content;
        this.account = account;
        this.modified_at = modified_at;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public int getModified_at() {
        return modified_at;
    }

    public void setModified_at(int modified_at) {
        this.modified_at = modified_at;
    }
}
