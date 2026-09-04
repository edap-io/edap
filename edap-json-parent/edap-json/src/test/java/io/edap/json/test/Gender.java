package io.edap.json.test;

import io.edap.json.annotation.JsonProperty;

public enum Gender {

    @JsonProperty("male")
    GENDER_MALE,
    @JsonProperty("female")
    GENDER_FEMALE;
}
