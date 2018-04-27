/*
 * Copyright 2017
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.ui.kb.stmt;

import static org.apache.commons.lang3.StringUtils.isNotEmpty;

import java.net.URI;
import java.util.Calendar;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.wicket.Component;
import org.apache.wicket.ajax.AjaxRequestTarget;
import org.apache.wicket.event.Broadcast;
import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.markup.html.form.Form;
import org.apache.wicket.markup.html.form.NumberTextField;
import org.apache.wicket.markup.html.form.TextField;
import org.apache.wicket.markup.html.panel.Fragment;
import org.apache.wicket.markup.html.panel.Panel;
import org.apache.wicket.model.CompoundPropertyModel;
import org.apache.wicket.model.IModel;
import org.apache.wicket.model.Model;
import org.apache.wicket.request.cycle.RequestCycle;
import org.apache.wicket.spring.injection.annot.SpringBean;
import org.apache.wicket.validation.IValidatable;
import org.apache.wicket.validation.IValidator;
import org.apache.wicket.validation.ValidationError;
import org.eclipse.rdf4j.model.vocabulary.XMLSchema;

import com.googlecode.wicket.kendo.ui.form.combobox.ComboBox;
import com.googlecode.wicket.kendo.ui.form.datetime.DatePicker;

import de.tudarmstadt.ukp.clarin.webanno.support.lambda.LambdaAjaxButton;
import de.tudarmstadt.ukp.clarin.webanno.support.lambda.LambdaAjaxFormComponentUpdatingBehavior;
import de.tudarmstadt.ukp.clarin.webanno.support.lambda.LambdaAjaxLink;
import de.tudarmstadt.ukp.inception.kb.KnowledgeBaseService;
import de.tudarmstadt.ukp.inception.kb.graph.KBProperty;
import de.tudarmstadt.ukp.inception.kb.graph.KBStatement;
import de.tudarmstadt.ukp.inception.kb.model.KnowledgeBase;
import de.tudarmstadt.ukp.inception.ui.kb.event.AjaxStatementChangedEvent;
import de.tudarmstadt.ukp.inception.ui.kb.util.WriteProtectionBehavior;

public class StatementEditor extends Panel {

    private static final long serialVersionUID = 7643837763550205L;

    private static final String CONTENT_MARKUP_ID = "content";

    private @SpringBean KnowledgeBaseService kbService;

    private IModel<KnowledgeBase> kbModel;
    private IModel<KBStatement> statement;
    private Component content;

    public StatementEditor(String aId, IModel<KnowledgeBase> aKbModel,
            IModel<KBStatement> aStatement) {
        super(aId, aStatement);

        setOutputMarkupId(true);

        kbModel = aKbModel;
        statement = aStatement;

        // new statements start with edit mode right away
        boolean isNewStatement = statement.getObject().getOriginalStatements().isEmpty();
        if (isNewStatement) {
            EditMode editMode = new EditMode(CONTENT_MARKUP_ID, statement, true);

            // obtain AjaxRequestTarget and set the focus
            AjaxRequestTarget target = RequestCycle.get()
                    .find(AjaxRequestTarget.class);
            if (target != null) {
                target.focusComponent(editMode.getFocusComponent());
            }
            content = editMode;
        } else {
            content = new ViewMode(CONTENT_MARKUP_ID, statement);
        }
        add(content);
    }

    protected void actionEdit(AjaxRequestTarget aTarget) {
        // Edit mode works on a model of a shallow copy of the original statement. Any floating
        // changes to the statement are either persisted by saving or undone by canceling. In
        // conjunction with onchange AjaxFormComponentUpdatingBehaviours, this makes sure that
        // floating changes are persisted in the UI, meaning other statements can be added or
        // deleted while changes to this statement in the UI are not being reset.
        KBStatement shallowCopy = new KBStatement(statement.getObject());
        IModel<KBStatement> shallowCopyModel = Model.of(shallowCopy);

        EditMode editMode = new EditMode(CONTENT_MARKUP_ID, shallowCopyModel, false);
        content = content.replaceWith(editMode);
        aTarget.focusComponent(editMode.getFocusComponent());
        aTarget.add(this);
    }

    private void actionCancelExistingStatement(AjaxRequestTarget aTarget) {
        content = content.replaceWith(new ViewMode(CONTENT_MARKUP_ID, statement));
        aTarget.add(this);
    }

    private void actionCancelNewStatement(AjaxRequestTarget aTarget) {
        // send a delete event to trigger the deletion in the UI
        AjaxStatementChangedEvent deleteEvent = new AjaxStatementChangedEvent(aTarget,
                statement.getObject(), this, true);
        send(getPage(), Broadcast.BREADTH, deleteEvent);
    }

    private void actionSave(AjaxRequestTarget aTarget, Form<KBStatement> aForm) {
        KBStatement modifiedStatement = aForm.getModelObject();
        
        // persist the modified statement and replace the original, unchanged model
        kbService.upsertStatement(kbModel.getObject(), modifiedStatement);
        statement.setObject(modifiedStatement);
        
        // switch back to ViewMode and send notification to listeners
        actionCancelExistingStatement(aTarget);
        send(getPage(), Broadcast.BREADTH,
                new AjaxStatementChangedEvent(aTarget, statement.getObject()));
    }

    private void actionDelete(AjaxRequestTarget aTarget) {
        kbService.deleteStatement(kbModel.getObject(), statement.getObject());

        AjaxStatementChangedEvent deleteEvent = new AjaxStatementChangedEvent(aTarget,
                statement.getObject(), this, true);
        send(getPage(), Broadcast.BREADTH, deleteEvent);
    }

    private void actionMakeExplicit(AjaxRequestTarget aTarget) {
        // add the statement as-is to the knowledge base
        kbService.upsertStatement(kbModel.getObject(), statement.getObject());

        // to update the statement in the UI, one could either reload all statements of the
        // corresponding instance or (much easier) just set the inferred attribute of the
        // KBStatement to false, so that's what's done here
        statement.getObject().setInferred(false);
        aTarget.add(this);
        send(getPage(), Broadcast.BREADTH,
                new AjaxStatementChangedEvent(aTarget, statement.getObject()));
    }

    private class ViewMode extends Fragment {
        private static final long serialVersionUID = 2375450134740203778L;

        public ViewMode(String aId, IModel<KBStatement> aStatement) {
            super(aId, "viewMode", StatementEditor.this, aStatement);

            CompoundPropertyModel<KBStatement> compoundModel = new CompoundPropertyModel<>(
                    aStatement);

            add(new Label("value", compoundModel.bind("value")));
            add(new Label("language", compoundModel.bind("language")) {
                private static final long serialVersionUID = 3436068825093393740L;

                @Override
                protected void onConfigure() {
                    super.onConfigure();
                    setVisible(isNotEmpty(aStatement.getObject().getLanguage()));
                }
            });
            
            LambdaAjaxLink editLink = new LambdaAjaxLink("edit", StatementEditor.this::actionEdit)
                    .onConfigure((_this) -> _this.setVisible(!statement.getObject().isInferred()));
            editLink.add(new WriteProtectionBehavior(kbModel));
            add(editLink);
            
            LambdaAjaxLink makeExplicitLink = new LambdaAjaxLink("makeExplicit",
                    StatementEditor.this::actionMakeExplicit).onConfigure(
                        (_this) -> _this.setVisible(statement.getObject().isInferred()));
            makeExplicitLink.add(new WriteProtectionBehavior(kbModel));
            add(makeExplicitLink);
        }
    }

    private class EditMode extends Fragment implements Focusable {
        private static final long serialVersionUID = 2489925553729209190L;

        private Component initialFocusComponent;
        private Fragment editCont;
        /**
         * Creates a new fragement for editing a statement.<br>
         * The editor has two slightly different behaviors, depending on the value of
         * {@code isNewStatement}:
         * <ul>
         * <li>{@code !isNewStatement}: Save button commits changes, cancel button discards unsaved
         * changes, delete button removes the statement from the KB.</li>
         * <li>{@code isNewStatement}: Save button commits changes (creates a new statement in the
         * KB), cancel button removes the statement from the UI, delete button is not visible.</li>
         * </ul>
         * 
         * @param aId
         *            markup ID
         * @param aStatement
         *            statement model
         * @param isNewStatement
         *            whether the statement being edited is new, meaning it has no corresponding
         *            statement in the KB backend
         */
        public EditMode(String aId, IModel<KBStatement> aStatement, boolean isNewStatement) {
            super(aId, "editMode", StatementEditor.this, aStatement);

            Form<KBStatement> form = new Form<>("form", CompoundPropertyModel.of(aStatement));

            
            //Determine datatype
            KBProperty prop = kbService.readProperty(kbModel.getObject(),
                aStatement.getObject().getProperty().getIdentifier()).get();
           
            // Get editor depending on datatype
            editCont = getValueEditorForDataType(prop.getRange(), "value");
            
            // text area for the statement value should receive focus
            initialFocusComponent = editCont.get("value");

            // FIXME This field should only be visible if the selected datatype is
            // langString
            form.add(new TextField<String>("language") {
                private static final long serialVersionUID = 5032496338439725046L;

                @Override
                protected void onConfigure() {
                    setVisible(XMLSchema.STRING.stringValue().equals(prop.getRange().toString()));
                }
            });

            // FIXME Selection of the data type should only be possible if it is not
            // restricted to a single type in the property definition - take into account
            // inheritance?
            //form.add(new TextField<>("datatype"));

            // We do not allow the user to change the property

            // FIXME should offer different editors depending on the data type
            // in particular when the datatype is a concept type, then
            // it should be possible to select an instance of that concept using some
            // auto-completing dropdown box

            form.add(new LambdaAjaxButton<>("save", StatementEditor.this::actionSave));
            form.add(new LambdaAjaxLink("cancel", t -> {
                if (isNewStatement) {
                    StatementEditor.this.actionCancelNewStatement(t);
                } else {
                    StatementEditor.this.actionCancelExistingStatement(t);
                }
            }));
            form.add(new LambdaAjaxLink("delete", StatementEditor.this::actionDelete)
                    .setVisibilityAllowed(!isNewStatement));
            
            form.add(editCont);
            add(form);
        }

        @Override
        public Component getFocusComponent() {
            return initialFocusComponent;
        }
            
        private Fragment getValueEditorForDataType(URI dataType, String markupId) {
            Fragment editorFragment = new Fragment("editCont", "editorStringMode",
                    StatementEditor.this);
            Component valueEditor;

            String dataTypeName = dataType.toString();

            if (XMLSchema.STRING.stringValue().equals(dataTypeName)) {
                valueEditor = new TextField<>(markupId);
            } 
            else if (XMLSchema.INTEGER.stringValue().equals(dataTypeName)) {
                // switch the fragment because we need a type=number input in html
                editorFragment = new Fragment("editCont", "editorNumberMode", StatementEditor.this);
                valueEditor = new NumberTextField<Integer>(markupId).setType(Integer.class);
            } 
            else if (XMLSchema.DATE.stringValue().equals(dataTypeName)) {
                valueEditor = new DatePicker(markupId);
            } 
            else if (XMLSchema.GYEAR.stringValue().equals(dataTypeName)) {
                // Create list with year values for ComboBox
                int currentYear = Calendar.getInstance().get(Calendar.YEAR);
                List<Integer> years = IntStream.rangeClosed(currentYear - 100, currentYear)
                        // reverse order
                        .map(i -> currentYear - i + currentYear - 100).boxed()
                        .collect(Collectors.toList());

                valueEditor = new ComboBox<Integer>(markupId, years).setType(Integer.class);
                        
            } 
            else {
                valueEditor = new TextField<>(markupId);
            }

            valueEditor.add(
                    new LambdaAjaxFormComponentUpdatingBehavior("change", t -> t.add(getParent())));
        
            editorFragment.add(valueEditor);
            return editorFragment;
        }
    }
    
    public class DataTypeValidator<T> implements IValidator<Object> {

        private static final long serialVersionUID = 3163644713929945135L;

        public void validate(IValidatable<Object> validatable) {

            ValidationError error = new ValidationError(this);
            error.setVariable("Wrong DataType. Value: ", validatable.getValue());
            validatable.error(error);
            
        }   
    }
    
}
