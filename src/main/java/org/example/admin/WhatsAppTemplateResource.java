package org.example.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.SecurityContext;
import org.example.notification.MetaOAuthService;
import org.example.notification.WhatsAppTemplateAssignments;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("admin/facebook-setup/templates")
@RolesAllowed("ADMIN")
public class WhatsAppTemplateResource {
    private static final Logger LOGGER =
            Logger.getLogger(WhatsAppTemplateResource.class.getName());
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DEFAULT_COMPONENTS = """
        [
          {
            "type": "BODY",
            "text": "Hello {{1}}, your parking booking is confirmed.",
            "example": {
              "body_text": [["Alex"]]
            }
          }
        ]
        """;

    @GET
    public Response list(@QueryParam("message") String message,
                         @Context HttpServletRequest request) {
        try {
            return page(request, MetaOAuthService.messageTemplates(), message,
                    null, Response.Status.OK);
        } catch (MetaOAuthService.MetaApiException exception) {
            return listError(request, exception.getMessage(), Response.Status.BAD_GATEWAY);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return listError(request, "The Meta template request was interrupted. Try again.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (IOException | SQLException exception) {
            LOGGER.log(Level.WARNING, "Unable to load WhatsApp message templates.", exception);
            return listError(request, "Unable to load templates. Check the database, "
                    + "encryption key, and employee system-user access.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Unable to render WhatsApp message templates.", exception);
            return listError(request, "Unable to display WhatsApp templates.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @POST
    @Path("assign")
    public Response assign(
            @FormParam("messageKey") String messageKey,
            @FormParam("templateId") String templateId,
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        try {
            WhatsAppTemplateAssignments.assign(messageKey, templateId,
                    currentAdminId(securityContext));
            return redirect(request, templateId == null || templateId.isBlank()
                    ? "The template assignment was removed."
                    : "The WhatsApp template assignment was saved.");
        } catch (MetaOAuthService.MetaApiException exception) {
            return listError(request, exception.getMessage(), Response.Status.BAD_REQUEST);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return listError(request, "The Meta template request was interrupted. Try again.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (IOException | SQLException exception) {
            LOGGER.log(Level.WARNING, "Unable to save WhatsApp template assignment.", exception);
            return listError(request, "Unable to save the template assignment. Check the database, "
                    + "encryption key, and employee system-user access.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Unexpected error saving WhatsApp template assignment.",
                    exception);
            return listError(request, "The template assignment could not be saved.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @GET
    @Path("new")
    public Response newTemplate(@Context HttpServletRequest request) {
        return editor(request, null, "utility", "en_US", "POSITIONAL",
                DEFAULT_COMPONENTS, null, Response.Status.OK);
    }

    @POST
    @Path("create")
    public Response create(
            @FormParam("name") String name,
            @FormParam("category") String category,
            @FormParam("language") String language,
            @FormParam("parameterFormat") String parameterFormat,
            @FormParam("components") String components,
            @Context HttpServletRequest request) {
        try {
            MetaOAuthService.createMessageTemplate(name, category, language,
                    parameterFormat, components);
            return redirect(request, "Template submitted to Meta for review.");
        } catch (MetaOAuthService.MetaApiException exception) {
            return editor(request, null, name, category, language, parameterFormat, components,
                    exception.getMessage(), Response.Status.BAD_REQUEST);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return editor(request, null, name, category, language, parameterFormat, components,
                    "The Meta template request was interrupted. Try again.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (IOException | SQLException exception) {
            LOGGER.log(Level.WARNING, "Unable to create WhatsApp message template.", exception);
            return editor(request, null, name, category, language, parameterFormat, components,
                    "Unable to create the template. Check the database, encryption key, "
                            + "and employee system-user access.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Unexpected error creating WhatsApp message template.",
                    exception);
            return editor(request, null, name, category, language, parameterFormat, components,
                    "The template could not be created due to an unexpected error.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @GET
    @Path("edit/{templateId}")
    public Response edit(@PathParam("templateId") String templateId,
                         @Context HttpServletRequest request) {
        try {
            MetaOAuthService.MessageTemplate template =
                    MetaOAuthService.messageTemplate(templateId);
            return editor(request, template, template.category(), template.language(),
                    template.details().path("parameter_format").asText("POSITIONAL"),
                    JSON.writerWithDefaultPrettyPrinter()
                            .writeValueAsString(template.components()),
                    null, Response.Status.OK);
        } catch (MetaOAuthService.MetaApiException exception) {
            return listError(request, exception.getMessage(), Response.Status.BAD_GATEWAY);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return listError(request, "The Meta template request was interrupted. Try again.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (IOException | SQLException exception) {
            LOGGER.log(Level.WARNING, "Unable to load WhatsApp template for editing.", exception);
            return listError(request, "Unable to load this template. Check the database, "
                    + "encryption key, and employee system-user access.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Unable to open WhatsApp template editor.", exception);
            return listError(request, "Unable to open this template.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @POST
    @Path("edit")
    public Response update(
            @FormParam("templateId") String templateId,
            @FormParam("category") String category,
            @FormParam("parameterFormat") String parameterFormat,
            @FormParam("allowCategoryChange") String allowCategoryChange,
            @FormParam("components") String components,
            @Context HttpServletRequest request) {
        try {
            MetaOAuthService.updateMessageTemplate(templateId, category, parameterFormat,
                    "true".equals(allowCategoryChange), components);
            return redirect(request, "Template update submitted to Meta for review.");
        } catch (MetaOAuthService.MetaApiException exception) {
            return editError(request, templateId, category, parameterFormat, components,
                    exception.getMessage(), Response.Status.BAD_REQUEST);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return editError(request, templateId, category, parameterFormat, components,
                    "The Meta template request was interrupted. Try again.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (IOException | SQLException exception) {
            LOGGER.log(Level.WARNING, "Unable to update WhatsApp message template.", exception);
            return editError(request, templateId, category, parameterFormat, components,
                    "Unable to update this template. Check the database, encryption key, "
                            + "and employee system-user access.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Unexpected error updating WhatsApp message template.",
                    exception);
            return editError(request, templateId, category, parameterFormat, components,
                    "The template could not be updated due to an unexpected error.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @POST
    @Path("delete")
    public Response delete(
            @FormParam("templateId") String templateId,
            @FormParam("name") String name,
            @Context HttpServletRequest request) {
        try {
            if (WhatsAppTemplateAssignments.isAssigned(templateId)) {
                return listError(request, "This template is assigned to a message. Change or "
                        + "remove that assignment before deleting the template.",
                        Response.Status.CONFLICT);
            }
            MetaOAuthService.deleteMessageTemplate(templateId, name);
            return redirect(request, "The selected template language was deleted.");
        } catch (MetaOAuthService.MetaApiException exception) {
            return listError(request, exception.getMessage(), Response.Status.BAD_GATEWAY);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return listError(request, "The Meta template request was interrupted. Try again.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (IOException | SQLException exception) {
            LOGGER.log(Level.WARNING, "Unable to delete WhatsApp message template.", exception);
            return listError(request, "Unable to delete the template. Check the database, "
                    + "encryption key, and employee system-user access.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Unexpected error deleting WhatsApp message template.",
                    exception);
            return listError(request, "The template could not be deleted.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @POST
    @Path("media")
    @Consumes(MediaType.WILDCARD)
    public Response uploadMedia(@HeaderParam(HttpHeaders.CONTENT_TYPE) String contentType,
                                @Context HttpServletRequest request,
                                InputStream media) {
        long contentLength = request.getContentLengthLong();
        try (InputStream input = media) {
            String handle = MetaOAuthService.uploadTemplateMedia(input, contentLength,
                    contentType);
            return Response.ok(JSON.createObjectNode().put("handle", handle))
                    .type(MediaType.APPLICATION_JSON).build();
        } catch (MetaOAuthService.MetaApiException exception) {
            return jsonError(exception.getMessage(), Response.Status.BAD_REQUEST);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return jsonError("The Meta media upload was interrupted. Try again.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (IOException | SQLException exception) {
            LOGGER.log(Level.WARNING, "Unable to upload WhatsApp template media.", exception);
            return jsonError("Unable to upload the media example. Check the database, "
                    + "encryption key, and employee system-user access.",
                    Response.Status.SERVICE_UNAVAILABLE);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Unexpected error uploading WhatsApp template media.",
                    exception);
            return jsonError("The media example could not be uploaded.",
                    Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    private Response editError(HttpServletRequest request, String templateId, String category,
                               String parameterFormat, String components, String error,
                               Response.Status status) {
        try {
            MetaOAuthService.MessageTemplate template =
                    MetaOAuthService.messageTemplate(templateId);
            return editor(request, template, category, template.language(), parameterFormat,
                    components, error, status);
        } catch (MetaOAuthService.MetaApiException | IOException | SQLException
                 | InterruptedException | RuntimeException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOGGER.log(Level.WARNING, "Unable to reload template after an edit error.", exception);
            return listError(request, error, status);
        }
    }

    private Response page(HttpServletRequest request,
                          List<MetaOAuthService.MessageTemplate> templates,
                          String message, String error, Response.Status status)
            throws SQLException {
        StringBuilder content = new StringBuilder();
        content.append("<h1>WhatsApp Templates</h1>")
                .append("<p>Templates belong to the connected WhatsApp Business Account. "
                        + "New and edited templates are reviewed by Meta before they can be used.</p>");
        appendNotice(content, message, error);
        appendAssignments(content, request, templates,
                WhatsAppTemplateAssignments.messageTypes());
        content.append("<p><a href=\"").append(escape(base(request)))
                .append("/api/admin/facebook-setup/templates/new\">Create template</a> | ")
                .append("<a href=\"").append(escape(base(request)))
                .append("/api/admin/facebook-setup\">Facebook / WhatsApp Setup</a></p>");
        if (templates.isEmpty()) {
            content.append("<p>No message templates were found.</p>");
        } else {
            content.append("<table><thead><tr><th>Name</th><th>Category</th><th>Language</th>")
                    .append("<th>Status</th><th>Content</th><th>Actions</th></tr></thead><tbody>");
            for (MetaOAuthService.MessageTemplate template : templates) {
                boolean editable = Set.of("APPROVED", "REJECTED")
                        .contains(template.status().toUpperCase());
                content.append("<tr><td>").append(escape(template.name()))
                        .append("</td><td>").append(escape(template.category()))
                        .append("</td><td>").append(escape(template.language()))
                        .append("</td><td>").append(escape(template.status()))
                        .append(statusDetail(template.details()))
                        .append("</td><td>").append(templatePreview(template.components()))
                        .append("</td><td>");
                if (editable) {
                    content.append("<a href=\"").append(escape(base(request)))
                            .append("/api/admin/facebook-setup/templates/edit/")
                            .append(escape(template.id())).append("\">Edit</a> ");
                } else {
                    content.append("<span title=\"Only approved or rejected templates can be edited\">")
                            .append("Edit unavailable</span> ");
                }
                content.append("<form method=\"post\" action=\"")
                        .append(escape(base(request)))
                        .append("/api/admin/facebook-setup/templates/delete\" ")
                        .append("onsubmit=\"return confirm('Delete this template language? "
                                + "This cannot be undone.');\">")
                        .append("<input type=\"hidden\" name=\"templateId\" value=\"")
                        .append(escape(template.id())).append("\">")
                        .append("<input type=\"hidden\" name=\"name\" value=\"")
                        .append(escape(template.name())).append("\">")
                        .append("<button type=\"submit\">Delete</button></form></td></tr>");
            }
            content.append("</tbody></table>");
        }
        return htmlResponse(request, "WhatsApp Templates", content.toString(), status);
    }

    private void appendAssignments(StringBuilder content, HttpServletRequest request,
                                   List<MetaOAuthService.MessageTemplate> templates,
                                   List<WhatsAppTemplateAssignments.MessageType> messageTypes) {
        content.append("<h2>Message template assignments</h2>")
                .append("<p>Only approved templates with the exact required number of body "
                        + "variables and no header or button variables can be assigned. "
                        + "OTP currently uses the existing booking template and cannot be changed here.</p>")
                .append("<table><thead><tr><th>Message</th><th>Assignment</th><th>Choose template</th>")
                .append("</tr></thead><tbody>");
        for (WhatsAppTemplateAssignments.MessageType type : messageTypes) {
            content.append("<tr><td>").append(escape(type.displayName()))
                    .append("<br><small>Requires exactly ")
                    .append(type.requiredBodyVariableCount()).append(" body variable")
                    .append(type.requiredBodyVariableCount() == 1 ? "" : "s")
                    .append("</small></td><td>");
            boolean assignable = WhatsAppTemplateAssignments.isAdminAssignable(type);
            if (!assignable) {
                content.append("Admin assignment disabled");
            } else if (type.templateId() == null) {
                content.append("No template assigned");
            } else {
                content.append(escape(type.templateName())).append(" (")
                        .append(escape(type.languageCode())).append(")");
            }
            content.append("</td><td>");
            if (assignable) {
                content.append("<form method=\"post\" action=\"")
                        .append(escape(base(request)))
                        .append("/api/admin/facebook-setup/templates/assign\">")
                        .append("<input type=\"hidden\" name=\"messageKey\" value=\"")
                        .append(escape(type.key())).append("\">")
                        .append("<select name=\"templateId\" aria-label=\"Template for ")
                        .append(escape(type.displayName())).append("\">");
                if (type.templateId() == null) {
                    content.append("<option value=\"\" selected>No template assigned</option>");
                } else {
                    content.append("<option value=\"\">Remove assignment</option>");
                }
                for (MetaOAuthService.MessageTemplate template : templates) {
                    boolean approved = "APPROVED".equalsIgnoreCase(template.status());
                    WhatsAppTemplateAssignments.Compatibility compatibility =
                            WhatsAppTemplateAssignments.compatibility(template.components(),
                                    type.requiredBodyVariableCount());
                    boolean eligible = approved && compatibility.compatible();
                    boolean selected = template.id().equals(type.templateId());
                    content.append("<option value=\"").append(escape(template.id())).append("\"");
                    if (selected) {
                        content.append(" selected");
                    }
                    if (!eligible) {
                        content.append(" disabled");
                    }
                    content.append(">")
                            .append(escape(template.name())).append(" (")
                            .append(escape(template.language())).append(") — ")
                            .append(escape(template.status()));
                    if (selected && !eligible) {
                        content.append(" — currently incompatible");
                    }
                    content.append("</option>");
                }
                content.append("</select><button type=\"submit\">Save assignment</button></form>");

                boolean hasUnavailable = templates.stream().anyMatch(template ->
                        !"APPROVED".equalsIgnoreCase(template.status())
                                || !WhatsAppTemplateAssignments.compatibility(
                                        template.components(),
                                        type.requiredBodyVariableCount()).compatible());
                if (hasUnavailable) {
                    content.append("<details><summary>Why other templates cannot be assigned</summary><ul>");
                    for (MetaOAuthService.MessageTemplate template : templates) {
                        WhatsAppTemplateAssignments.Compatibility compatibility =
                                WhatsAppTemplateAssignments.compatibility(template.components(),
                                        type.requiredBodyVariableCount());
                        if (!"APPROVED".equalsIgnoreCase(template.status())) {
                            content.append("<li>").append(escape(template.name()))
                                    .append(": must be approved (current status: ")
                                    .append(escape(template.status())).append(").</li>");
                        } else if (!compatibility.compatible()) {
                            content.append("<li>").append(escape(template.name())).append(": ")
                                    .append(escape(compatibility.reason())).append("</li>");
                        }
                    }
                    content.append("</ul></details>");
                }
            } else {
                content.append("OTP template selection will be enabled when an OTP template is available.");
            }
            content.append("</td></tr>");
        }
        content.append("</tbody></table>");
    }

    private String statusDetail(JsonNode details) {
        String rejected = details.path("rejected_reason").asText("");
        String quality = details.path("quality_score").path("score").asText("");
        String extra = !rejected.isBlank() ? rejected
                : !quality.isBlank() ? "Quality: " + quality : "";
        return extra.isBlank() ? "" : "<br><small>" + escape(extra) + "</small>";
    }

    private String templatePreview(JsonNode components) {
        StringBuilder preview = new StringBuilder("<div class=\"template-preview\">");
        if (components != null && components.isArray()) {
            for (JsonNode component : components) {
                String type = component.path("type").asText("").toUpperCase();
                switch (type) {
                    case "HEADER" -> appendHeader(preview, component);
                    case "BODY" -> preview.append("<div class=\"preview-body\">")
                            .append(formatText(component.path("text").asText("")))
                            .append(exampleText(component)).append("</div>");
                    case "FOOTER" -> preview.append("<div class=\"preview-footer\">")
                            .append(formatText(component.path("text").asText("")))
                            .append("</div>");
                    case "BUTTONS" -> appendButtons(preview, component.path("buttons"));
                    default -> preview.append("<details><summary>")
                            .append(escape(type.isBlank() ? "Component" : type))
                            .append("</summary><pre>")
                            .append(escape(component.toPrettyString()))
                            .append("</pre></details>");
                }
            }
        }
        return preview.append("</div>").toString();
    }

    private void appendHeader(StringBuilder preview, JsonNode component) {
        String format = component.path("format").asText("TEXT").toUpperCase();
        String text = component.path("text").asText("");
        preview.append("<div class=\"preview-header\">");
        if (!text.isBlank()) {
            preview.append(formatText(text));
        } else {
            preview.append("<em>").append(escape(format)).append(" header");
            if (component.path("example").has("header_handle")) {
                preview.append(" (media example provided)");
            }
            preview.append("</em>");
        }
        preview.append("</div>");
    }

    private void appendButtons(StringBuilder preview, JsonNode buttons) {
        if (!buttons.isArray()) {
            preview.append("<details><summary>Buttons</summary><pre>")
                    .append(escape(buttons.toPrettyString())).append("</pre></details>");
            return;
        }
        preview.append("<div class=\"preview-buttons\">");
        for (JsonNode button : buttons) {
            String label = button.path("text").asText(
                    button.path("type").asText("Interactive button"));
            preview.append("<span>").append(escape(label)).append("</span>");
        }
        preview.append("</div>");
    }

    private String exampleText(JsonNode component) {
        JsonNode example = component.path("example").path("body_text");
        if (!example.isArray() || example.isEmpty() || !example.get(0).isArray()) {
            return "";
        }
        return "<br><small>Example: " + escape(example.get(0).toString()) + "</small>";
    }

    private String formatText(String text) {
        return escape(text).replace("\n", "<br>");
    }

    private Response editor(HttpServletRequest request,
                            MetaOAuthService.MessageTemplate template,
                            String category, String language, String parameterFormat,
                            String components, String error, Response.Status status) {
        return editor(request, template, template == null ? "" : template.name(), category,
                language, parameterFormat, components, error, status);
    }

    private Response editor(HttpServletRequest request,
                            MetaOAuthService.MessageTemplate template, String templateName,
                            String category, String language, String parameterFormat,
                            String components, String error, Response.Status status) {
        boolean editing = template != null;
        boolean editable = !editing || Set.of("APPROVED", "REJECTED")
                .contains(template.status().toUpperCase());
        String action = editing ? "/api/admin/facebook-setup/templates/edit"
                : "/api/admin/facebook-setup/templates/create";
        StringBuilder content = new StringBuilder("<h1>")
                .append(editing ? "Edit WhatsApp Template" : "Create WhatsApp Template")
                .append("</h1>");
        if (error != null) {
            content.append("<p class=\"error\" role=\"alert\">").append(escape(error))
                    .append("</p>");
        }
        if (editing) {
            content.append("<p>Name: <strong>").append(escape(template.name()))
                    .append("</strong> | Language: <strong>").append(escape(template.language()))
                    .append("</strong> | Current status: <strong>")
                    .append(escape(template.status())).append("</strong></p>");
        }
        if (!editable) {
            content.append("<p>This template cannot be edited unless its status is approved "
                    + "or rejected. You may still review its components below.</p>");
        }
        content.append("<form method=\"post\" action=\"").append(escape(base(request)))
                .append(action).append("\">");
        if (editing) {
            content.append("<input type=\"hidden\" name=\"templateId\" value=\"")
                    .append(escape(template.id())).append("\">");
        } else {
            content.append("<label for=\"name\">Template name</label><br>")
                    .append("<input id=\"name\" name=\"name\" maxlength=\"512\" ")
                    .append("pattern=\"[a-z0-9_]+\" required value=\"")
                    .append(escape(defaultValue(templateName, "")))
                    .append("\"><p>Use lowercase letters, numbers, and underscores.</p>");
        }
        content.append("<label for=\"category\">Category</label><br>")
                .append("<select id=\"category\" name=\"category\" required>")
                .append(option("AUTHENTICATION", category))
                .append(option("MARKETING", category))
                .append(option("UTILITY", category)).append("</select><br><br>");
        if (editing) {
            content.append("<label><input type=\"checkbox\" name=\"allowCategoryChange\" ")
                    .append("value=\"true\"> Allow Meta to reassign the category</label><br><br>");
        } else {
            content.append("<label for=\"language\">Language code</label><br>")
                    .append("<input id=\"language\" name=\"language\" maxlength=\"20\" ")
                    .append("pattern=\"[A-Za-z]{2,3}(_[A-Za-z0-9]{2,8})?\" required value=\"")
                    .append(escape(defaultValue(language, "en_US"))).append("\"><br><br>");
        }
        content.append("<label for=\"parameterFormat\">Variable format</label><br>")
                .append("<select id=\"parameterFormat\" name=\"parameterFormat\">")
                .append("<option value=\"POSITIONAL\"")
                .append(selected("POSITIONAL", parameterFormat))
                .append(">Positional ({{1}}, {{2}})</option>")
                .append("<option value=\"NAMED\"").append(selected("NAMED", parameterFormat))
                .append(">Named ({{first_name}})</option></select><br><br>");
        content.append("<p>Components support header text or media, body text and examples, "
                + "footer, buttons, carousels, authentication and other Meta component types. "
                + "Use valid WhatsApp template component JSON; Meta validates policy and component "
                + "rules when you submit.</p>")
                .append("<label for=\"components\">Template components (JSON)</label><br>")
                .append("<label for=\"mediaFile\">Upload a header image example (JPEG or PNG, maximum 5 MB)</label><br>")
                .append("<input id=\"mediaFile\" type=\"file\" accept=\"image/jpeg,image/png\">")
                .append("<button id=\"uploadMedia\" type=\"button\" data-upload-url=\"")
                .append(escape(base(request)))
                .append("/api/admin/facebook-setup/templates/media\">Upload and add to header</button>")
                .append("<span id=\"uploadStatus\" role=\"status\" aria-live=\"polite\"></span><br><br>")
                .append("<textarea id=\"components\" name=\"components\" rows=\"22\" ")
                .append("maxlength=\"65536\" required>")
                .append(escape(defaultValue(components, DEFAULT_COMPONENTS)))
                .append("</textarea>")
                .append("<h2>WhatsApp preview</h2><div id=\"preview\" class=\"template-preview\">")
                .append(templatePreview(parseComponents(components))).append("</div>");
        if (editable) {
            content.append("<button type=\"submit\">")
                    .append(editing ? "Submit template changes" : "Submit template for review")
                    .append("</button>");
        }
        content.append("</form><p><a href=\"").append(escape(base(request)))
                .append("/api/admin/facebook-setup/templates\">Back to templates</a></p>")
                .append("<script>")
                .append("const editor=document.getElementById('components');")
                .append("const preview=document.getElementById('preview');")
                .append("if(editor&&preview){editor.addEventListener('input',()=>{")
                .append("try{const items=JSON.parse(editor.value);")
                .append("if(!Array.isArray(items))throw new Error('Expected a JSON array');")
                .append("preview.replaceChildren();")
                .append("for(const item of items){const section=document.createElement('section');")
                .append("section.className='preview-part';const heading=document.createElement('strong');")
                .append("heading.textContent=item.type||'Component';section.append(heading);")
                .append("const text=document.createElement('p');text.textContent=item.text||")
                .append("(item.format?item.format+' component':'');section.append(text);")
                .append("if(item.buttons){for(const button of item.buttons){")
                .append("const b=document.createElement('span');b.textContent=button.text||button.type;")
                .append("section.append(b)}}preview.append(section)}}catch(e){")
                .append("preview.textContent='Preview unavailable: '+e.message}})}")
                .append("const upload=document.getElementById('uploadMedia');")
                .append("if(upload){upload.addEventListener('click',async()=>{")
                .append("const file=document.getElementById('mediaFile').files[0];")
                .append("const status=document.getElementById('uploadStatus');")
                .append("if(!file){status.textContent=' Choose a file first.';return}")
                .append("status.textContent=' Uploading…';upload.disabled=true;")
                .append("try{const response=await fetch(upload.dataset.uploadUrl,{method:'POST',")
                .append("headers:{'Content-Type':file.type},body:file});")
                .append("const result=await response.json();if(!response.ok)throw new Error(result.error||'Upload failed');")
                .append("const items=JSON.parse(editor.value);if(!Array.isArray(items))throw new Error('Components must be a JSON array');")
                .append("let header=items.find(item=>String(item.type).toUpperCase()==='HEADER');")
                .append("if(!header){header={type:'HEADER'};items.unshift(header)}")
                .append("header.format='IMAGE';")
                .append("header.example=header.example||{};header.example.header_handle=[result.handle];")
                .append("editor.value=JSON.stringify(items,null,2);editor.dispatchEvent(new Event('input'));")
                .append("status.textContent=' Media uploaded and added to the header example.'")
                .append("}catch(e){status.textContent=' '+e.message}finally{upload.disabled=false}})}")
                .append("</script>");
        return htmlResponse(request, editing ? "Edit WhatsApp Template"
                : "Create WhatsApp Template", content.toString(), status);
    }

    private JsonNode parseComponents(String components) {
        try {
            return JSON.readTree(defaultValue(components, DEFAULT_COMPONENTS));
        } catch (IOException exception) {
            return JSON.createArrayNode();
        }
    }

    private String option(String value, String selected) {
        return "<option value=\"" + value + "\"" + selected(value, selected) + ">"
                + value.substring(0, 1) + value.substring(1).toLowerCase() + "</option>";
    }

    private String selected(String value, String selected) {
        return value.equalsIgnoreCase(defaultValue(selected, "")) ? " selected" : "";
    }

    private String defaultValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private Response listError(HttpServletRequest request, String error, Response.Status status) {
        try {
            return page(request, MetaOAuthService.messageTemplates(), null, error, status);
        } catch (MetaOAuthService.MetaApiException | IOException | SQLException
                 | InterruptedException | RuntimeException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }

            LOGGER.log(Level.WARNING, "Unable to reload WhatsApp templates after an error.",
                    exception);
            String content = "<h1>WhatsApp Templates</h1><p class=\"error\" role=\"alert\">"
                    + escape(error) + "</p><p><a href=\"" + escape(base(request))
                    + "/api/admin/facebook-setup\">Back to Facebook / WhatsApp Setup</a></p>";
            return htmlResponse(request, "WhatsApp Templates", content, status);
        }
    }

    private long currentAdminId(SecurityContext securityContext) {
        if (securityContext == null || securityContext.getUserPrincipal() == null) {
            throw new IllegalStateException("The authenticated administrator is unavailable.");
        }
        return Long.parseLong(securityContext.getUserPrincipal().getName());
    }

    private void appendNotice(StringBuilder content, String message, String error) {
        if (message != null && !message.isBlank()) {
            content.append("<p role=\"status\">").append(escape(message)).append("</p>");
        }
        if (error != null && !error.isBlank()) {
            content.append("<p class=\"error\" role=\"alert\">")
                    .append(escape(error)).append("</p>");
        }
    }

    private Response jsonError(String message, Response.Status status) {
        return Response.status(status).type(MediaType.APPLICATION_JSON)
                .entity(JSON.createObjectNode().put("error", message)).build();
    }

    private Response redirect(HttpServletRequest request, String message) {
        String location = base(request) + "/api/admin/facebook-setup/templates?message="
                + URLEncoder.encode(message, StandardCharsets.UTF_8);
        return Response.seeOther(URI.create(location)).build();
    }

    private Response htmlResponse(HttpServletRequest request, String title, String content,
                                  Response.Status status) {
        String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>" + escape(title) + "</title><style>"
                + "body{font:16px system-ui,sans-serif;max-width:1200px;margin:2rem auto;padding:0 1rem;color:#182230}"
                + "table{width:100%;border-collapse:collapse}th,td{border:1px solid #ccd2da;padding:.7rem;vertical-align:top;text-align:left}"
                + "textarea{width:100%;max-width:100%;font:14px ui-monospace,monospace}"
                + "input,select,button{font:inherit;padding:.4rem}button{cursor:pointer}"
                + ".error{color:#a40000;background:#fff0f0;padding:.8rem}"
                + ".template-preview{max-width:420px;background:#e7f4e4;border-radius:12px;padding:.8rem;white-space:pre-wrap;overflow-wrap:anywhere}"
                + ".preview-header,.preview-body,.preview-footer{margin:.4rem 0}"
                + ".preview-header{font-weight:bold}.preview-footer{color:#58616b;font-size:.9rem}"
                + ".preview-buttons span,#preview span{display:inline-block;color:#0866ff;margin:.3rem;padding:.35rem .7rem;border:1px solid #ccd2da;border-radius:18px}"
                + "form{margin:.25rem 0}pre{white-space:pre-wrap;overflow-wrap:anywhere}"
                + "</style></head><body><h1>Parking Management System</h1>"
                + content + "<p><a href=\"" + escape(base(request))
                + "/api/admin\">Admin Dashboard</a></p></body></html>";
        return Response.status(status).type(MediaType.TEXT_HTML)
                .header("Cache-Control", "no-store")
                .entity(html).build();
    }

    private String base(HttpServletRequest request) {
        String configured = System.getenv("APP_BASE_URL");
        return configured == null || configured.isBlank()
                ? request.getContextPath() : configured.trim();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
