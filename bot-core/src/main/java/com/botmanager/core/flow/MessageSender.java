package com.botmanager.core.flow;

import java.util.List;

public interface MessageSender {

    void sendText(String to, String body);

    void sendButtons(String to, String body, List<FlowState.ButtonOption> buttons);

    void sendList(String to, ListMessage message);

    record ListRow(String id, String title, String description) {}

    record ListSection(String title, List<ListRow> rows) {}

    record ListMessage(String body, String buttonText, List<ListSection> sections) {}

}
