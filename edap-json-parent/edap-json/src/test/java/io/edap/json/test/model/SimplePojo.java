package io.edap.json.test.model;

public class SimplePojo {

    private Long id;        // ← 包装类型 Long
    private String name;


    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
