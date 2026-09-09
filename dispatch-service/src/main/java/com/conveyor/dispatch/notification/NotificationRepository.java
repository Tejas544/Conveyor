package com.conveyor.dispatch.notification;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface NotificationRepository extends MongoRepository<NotificationDocument, String> {

  List<NotificationDocument> findByOrderId(String orderId);
}
