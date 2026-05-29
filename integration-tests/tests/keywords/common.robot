*** Settings ***
Library     RequestsLibrary
Library     JSONLibrary
Resource    ../resources/variables.robot

*** Keywords ***
Create Session To Bot
    [Documentation]    Opens an HTTP session to the spring-bot-manager-only service
    Create Session    bot    ${BASE_URL}    verify=False

Delete Session To Bot
    Delete All Sessions

Build WhatsApp Text Message Payload
    [Documentation]    Returns a WhatsApp Cloud API inbound text message payload
    [Arguments]    ${from_number}=${CUSTOMER_PHONE}    ${message_text}=hello
    ...            ${phone_number_id}=${PHONE_NUMBER_ID}    ${bot_id}=${BOT_ID}
    ${payload}=    Evaluate
    ...    {"object": "whatsapp_business_account", "entry": [{"id": "ENTRY-1", "changes": [{"value": {"messaging_product": "whatsapp", "metadata": {"display_phone_number": "237650000000", "phone_number_id": "${phone_number_id}"}, "contacts": [{"profile": {"name": "Test User"}, "wa_id": "${from_number}"}], "messages": [{"from": "${from_number}", "id": "wamid.TEST001", "timestamp": "1700000000", "text": {"body": "${message_text}"}, "type": "text"}]}, "field": "messages"}]}]}
    RETURN    ${payload}

Build WhatsApp Button Reply Payload
    [Documentation]    Returns a WhatsApp button reply payload
    [Arguments]    ${from_number}=${CUSTOMER_PHONE}    ${button_id}=action_wash
    ...            ${phone_number_id}=${PHONE_NUMBER_ID}
    ${payload}=    Evaluate
    ...    {"object": "whatsapp_business_account", "entry": [{"id": "ENTRY-1", "changes": [{"value": {"messaging_product": "whatsapp", "metadata": {"display_phone_number": "237650000000", "phone_number_id": "${phone_number_id}"}, "contacts": [{"profile": {"name": "Test User"}, "wa_id": "${from_number}"}], "messages": [{"from": "${from_number}", "id": "wamid.TEST002", "timestamp": "1700000000", "interactive": {"type": "button_reply", "button_reply": {"id": "${button_id}", "title": "Start Wash"}}, "type": "interactive"}]}, "field": "messages"}]}]}
    RETURN    ${payload}

Post WhatsApp Webhook
    [Arguments]    ${payload}
    ${resp}=    POST On Session    bot    /api/whatsapp/webhook    json=${payload}    expected_status=200
    RETURN    ${resp.json()}

Get Machines For Bot
    [Arguments]    ${bot_id}=${BOT_ID}
    ${resp}=    GET On Session    bot    /api/machines/${bot_id}    expected_status=200
    RETURN    ${resp.json()}

Get Machine For Bot
    [Arguments]    ${bot_id}    ${machine_id}
    ${resp}=    GET On Session    bot    /api/machines/${bot_id}/${machine_id}    expected_status=200
    RETURN    ${resp.json()}

Get Available Machines For Bot
    [Arguments]    ${bot_id}=${BOT_ID}
    ${resp}=    GET On Session    bot    /api/machines/${bot_id}/available    expected_status=200
    RETURN    ${resp.json()}

Post CamPay Webhook For Bot
    [Arguments]    ${bot_id}    ${payload}
    ${resp}=    POST On Session    bot    /api/payments/webhooks/campay/${bot_id}
    ...    json=${payload}    expected_status=200
    RETURN    ${resp.json()}
