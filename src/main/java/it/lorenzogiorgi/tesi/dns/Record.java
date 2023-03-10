package it.lorenzogiorgi.tesi.dns;

public class Record {
    private String content;
    private boolean disabled;

    public Record(String content, boolean disabled) {
        this.content = content;
        this.disabled = disabled;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isDisabled() {
        return disabled;
    }

    public void setDisabled(boolean disabled) {
        this.disabled = disabled;
    }
}




