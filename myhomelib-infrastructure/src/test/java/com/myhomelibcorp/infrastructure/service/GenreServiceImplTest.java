package com.myhomelibcorp.infrastructure.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GenreServiceImplTest {

    @Test
    void loadsFb2GenreCodesFromResource() {
        GenreServiceImpl service = new GenreServiceImpl();

        service.init();

        assertThat(service.getGenreName("sf")).isEqualTo("Научная фантастика");
        assertThat(service.getGenreName("sf_fantasy")).isEqualTo("Фэнтези");
        assertThat(service.getGenreName("det_classic")).isEqualTo("Классический детектив");
        assertThat(service.getAllGenreNames()).contains("Фантастика", "Научная фантастика");
    }
}
