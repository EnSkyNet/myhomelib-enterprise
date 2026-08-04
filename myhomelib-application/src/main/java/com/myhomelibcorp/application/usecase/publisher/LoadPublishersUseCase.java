package com.myhomelibcorp.application.usecase.publisher;

import com.myhomelibcorp.application.port.out.repository.PublisherRepository;
import com.myhomelibcorp.domain.model.publisher.Publisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class LoadPublishersUseCase {

    private final PublisherRepository publisherRepository;

    public List<Publisher> execute() {
        return publisherRepository.findAll();
    }

    public List<Publisher> findTop(int limit) {
        return publisherRepository.findTop(limit);
    }

    public List<Publisher> searchByName(String name) {
        if (name == null || name.isBlank()) {
            return execute();
        }
        return publisherRepository.findByNameContaining(name);
    }
}