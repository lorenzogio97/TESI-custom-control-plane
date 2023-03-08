package it.lorenzogiorgi.tesi.beans;

public class Record {
    String content;
    boolean disabled;

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




