package org.coode.owlapi.obo.renderer;

import org.coode.owlapi.obo.parser.OBOVocabulary;
import org.semanticweb.owlapi.io.AbstractOWLRenderer;
import org.semanticweb.owlapi.io.OWLRendererException;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.util.NamespaceUtil;
import org.semanticweb.owlapi.util.SimpleShortFormProvider;
import org.semanticweb.owlapi.util.VersionInfo;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;

import java.io.IOException;
import java.io.Writer;
import java.net.URI;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
/*
* Copyright (C) 2008, University of Manchester
*
* Modifications to the initial code base are copyright of their
* respective authors, or their employers as appropriate.  Authorship
* of the modifications may be determined from the ChangeLog placed at
* the end of this file.
*
* This library is free software; you can redistribute it and/or
* modify it under the terms of the GNU Lesser General Public
* License as published by the Free Software Foundation; either
* version 2.1 of the License, or (at your option) any later version.

* This library is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
* Lesser General Public License for more details.

* You should have received a copy of the GNU Lesser General Public
* License along with this library; if not, write to the Free Software
* Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
*/

/**
 * Author: Nick Drummond<br>
 * The University Of Manchester<br>
 * Bio Health Informatics Group<br>
 * Date: Dec 17, 2008<br><br>
 * <p/>
 * Renders OBO 1.2 flat file format.
 * OBO 1.2 is a subset of OWL, so a rendering of an arbitrary OWL ontology may be incomplete in OBO.
 * <p/>
 * <p/>
 * Several features are currently not implemented:
 * - Exception handling for unsupported constructs - these are currently reported in stderr
 * - axiom annotations (these might be serialisable as inline comments - although OBO parsers provide no
 * guarantees these will be roundtripped)
 * - anonymous classes/properties - it is not clear what this means in OBO
 * - datatype restrictions
 * - namespace and derived tags for relationships etc - it is not clear what this means in OBO
 * - preservation of ordering on roundtripping exercises
 * - Stanzas are ordered (classes, obj/data props then instances)
 * - Entities are ordered by ID in each Stanza type
 * - OBO tags are ordered WRT the OBO specifications
 * <p/>
 * Additional points:
 * - cardinality is expressed as an '=' separated tag value pair in [1] and underspecified in [2]. OBOEdit 2
 * does not parse this (at the time of writing) so this has been changed to standard TVP syntax
 * - OBO 1.2 currently specifies pairwise disjointness which this renderer follows so files may get large
 * - Exceptions are caught along the way and then wrapped in an OBOStorageIncompleteException which is thrown
 * after serialisation ends
 * <p/>
 * <p/>
 * References:
 * [1] http://www.cs.man.ac.uk/~horrocks/obo/
 * [2] http://www.geneontology.org/GO.format.obo-1_2.shtml
 */
public class OBOFlatFileRenderer extends AbstractOWLRenderer implements OBOExceptionHandler {

    private OBORelationshipGenerator relationshipHandler;

    private SimpleShortFormProvider sfp;

    private String defaultPrefix;

    private List<OBOStorageException> exceptions = new ArrayList<OBOStorageException>();

    private NamespaceUtil nsUtil;

    private String defaultNamespace;


    protected OBOFlatFileRenderer(OWLOntologyManager owlOntologyManager) {
        super(owlOntologyManager);
        relationshipHandler = new OBORelationshipGenerator(this);
        sfp = new SimpleShortFormProvider();
    }


    public void render(OWLOntology ontology, Writer writer) throws OWLRendererException {
        exceptions.clear();

        final String ontURIStr = ontology.getOntologyID().getOntologyIRI().toString();
        if (ontURIStr.endsWith("/")) {
            defaultNamespace = ontURIStr;
        } else {
            defaultNamespace = ontURIStr + "#";
        }

        nsUtil = new NamespaceUtil();
        defaultPrefix = nsUtil.getPrefix(defaultNamespace);

        writeHeader(ontology, writer);
        writeStanzas(ontology, writer);

        if (!exceptions.isEmpty()) {
            throw new OBOStorageIncompleteException(exceptions);
        }
    }


    public void addException(OBOStorageException exception) {
        exceptions.add(exception);
    }


    public List<OBOStorageException> getExceptions() {
        return exceptions;
    }


    private void writeHeader(OWLOntology ontology, Writer writer) throws OWLRendererException {
        OBOTagValuePairList tvpList = new OBOTagValuePairList(OBOVocabulary.getHeaderTags());

        tvpList.setDefault(OBOVocabulary.DEFAULT_NAMESPACE, defaultPrefix);

        for (OWLAnnotation ax : ontology.getAnnotations()) {
            if (ax.getProperty().isComment()) {
                tvpList.addPair(OBOVocabulary.REMARK, ((OWLLiteral) ax.getValue()).getLiteral());
            } else {
                tvpList.visit(ax);
            }
        }

        for (OWLImportsDeclaration importDecl : ontology.getImportsDeclarations()) {
            tvpList.addPair(OBOVocabulary.IMPORT, importDecl.getURI().toString());
        }

        Map<String, String> namespace2PrefixMap = loadUsedNamespaces(ontology);
        for (String namespace : namespace2PrefixMap.keySet()) {
            String mapping = namespace2PrefixMap.get(namespace) + " " + namespace;
            tvpList.addPair(OBOVocabulary.ID_SPACE, mapping);
        }

        // overwrite the existing values for below
        tvpList.setPair(OBOVocabulary.FORMAT_VERSION, "1.2");
        tvpList.setPair(OBOVocabulary.DATE, getTimestampFormatter().format(new Date(System.currentTimeMillis())));
        tvpList.setPair(OBOVocabulary.SAVED_BY, System.getProperty("user.name"));
        tvpList.setPair(OBOVocabulary.AUTO_GENERATED_BY, VersionInfo.getVersionInfo().toString());

        tvpList.write(writer);
    }


    private Map<String, String> loadUsedNamespaces(OWLOntology ontology) {
        for (OWLEntity entity : ontology.getReferencedEntities()) {
            String[] pair = new String[2];
            nsUtil.split(entity.getURI().toString(), pair);
            final URI base = URI.create(pair[0]);
            nsUtil.getPrefix(base.toString());
        }
        return nsUtil.getNamespace2PrefixMap();
    }


    private void writeStanzas(OWLOntology ontology, Writer writer) {

        write("\n\n! ----------------------  CLASSES  -------------------------\n", writer);

        final List<OWLClass> sortedClasses = new ArrayList<OWLClass>(ontology.getReferencedClasses());
        Collections.sort(sortedClasses);
        for (OWLClass cls : sortedClasses) {
            writeTermStanza(cls, ontology, writer);
        }


        write("\n\n! ----------------------  PROPERTIES  -------------------------\n", writer);

        final List<OWLObjectProperty> objProps = new ArrayList<OWLObjectProperty>(ontology.getReferencedObjectProperties());
        Collections.sort(objProps);
        for (OWLObjectProperty property : objProps) {
            writeTypeDefStanza(property, ontology, writer);
        }

        final List<OWLDataProperty> dataProps = new ArrayList<OWLDataProperty>(ontology.getReferencedDataProperties());
        Collections.sort(dataProps);
        for (OWLDataProperty property : dataProps) {
            writeTypeDefStanza(property, ontology, writer);
        }


        write("\n\n! ----------------------  INSTANCES  -------------------------\n", writer);

        final List<OWLNamedIndividual> individuals = new ArrayList<OWLNamedIndividual>(ontology.getReferencedIndividuals());
        Collections.sort(individuals);
        for (OWLNamedIndividual individual : individuals) {
            writeInstanceStanza(individual, ontology, writer);
        }

        // scan ontology for other constructs that cannot be translated

        for (OWLClassAxiom ax : ontology.getClassAxioms()) {
            if (ax instanceof OWLSubClassOfAxiom && ((OWLSubClassOfAxiom) ax).isGCI()) {
                exceptions.add(new OBOStorageException(ax, null, "Superclass GCI found in ontology cannot be translated to OBO"));
            } else
            if (ax instanceof OWLEquivalentClassesAxiom && ((OWLEquivalentClassesAxiom) ax).getNamedClasses().isEmpty()) {
                exceptions.add(new OBOStorageException(ax, null, "Equivalent class GCI found in ontology cannot be translated to OBO"));
            } else if (ax instanceof OWLDisjointClassesAxiom) {
                for (OWLClassExpression op : ((OWLDisjointClassesAxiom) ax).getClassExpressions()) {
                    if (op.isAnonymous()) {
                        exceptions.add(new OBOStorageException(ax, null, "Disjoint axiom contains anonymous classes - cannot be translated to OBO"));
                        break;
                    }
                }
            }
        }

        for (SWRLRule r : ontology.getRules()) {
            exceptions.add(new OBOStorageException(r, null, "SWRL rules cannot be translated to OBO"));
        }

    }


    private void writeTermStanza(OWLClass cls, OWLOntology ontology, Writer writer) {
        write("\n[", writer);
        write(OBOVocabulary.TERM.getName(), writer);
        write("]\n", writer);

        // TODO is_anonymous??

        OBOTagValuePairList tvpList = new OBOTagValuePairList(OBOVocabulary.getTermStanzaTags());

        handleEntityBase(cls, ontology, tvpList);

        relationshipHandler.setClass(cls);

        for (OWLClassExpression superCls : cls.getSuperClasses(ontology)) {
            if (!superCls.isAnonymous()) {
                String superclassID = getID(superCls.asOWLClass());
                tvpList.addPair(OBOVocabulary.IS_A, superclassID);
                // TODO handle namespace and derived?
            } else if (superCls instanceof OWLRestriction) {
                // TODO handle datatype restrictions
                superCls.accept(relationshipHandler);
            } else {
                exceptions.add(new OBOStorageException(cls, superCls, "OBO format only supports named superclass or someValuesFrom restrictions"));
            }
        }

        final OWLClass owlThing = getOWLOntologyManager().getOWLDataFactory().getOWLThing();

        // if no named superclass is specified, then this must be asserted to be a subclass of owlapi:Thing
        if (!cls.equals(owlThing) && tvpList.getValues(OBOVocabulary.IS_A).isEmpty()) {
            tvpList.addPair(OBOVocabulary.IS_A, getID(owlThing));
        }

        for (OWLClassExpression equiv : cls.getEquivalentClasses(ontology)) {
            if (equiv instanceof OWLObjectIntersectionOf) {
                handleIntersection(cls, (OWLObjectIntersectionOf) equiv, tvpList);
            } else if (equiv instanceof OWLObjectUnionOf) {
                handleUnion(cls, (OWLObjectUnionOf) equiv, tvpList);
            } else if (equiv instanceof OWLRestriction) {
                /* OBO equivalence must be of the form "A and p some B and ..."
                 * if this class is equiv to a restriction, put this into an intersection with owlapi:Thing as the named class
                 */
                OWLObjectIntersectionOf intersection = getOWLOntologyManager().getOWLDataFactory().getOWLObjectIntersectionOf(owlThing, equiv);
                handleIntersection(cls, intersection, tvpList);
            } else {
                // TODO handle datatype restrictions
                exceptions.add(new OBOStorageException(cls, equiv, "Cannot answer equivalent class that is not intersection or union"));
            }
        }

        for (OWLClassExpression disjoint : cls.getDisjointClasses(ontology)) {
            if (!disjoint.isAnonymous()) {
                tvpList.addPair(OBOVocabulary.DISJOINT_FROM, getID(disjoint.asOWLClass()));
                // TODO handle namespace and derived
            } else {
                exceptions.add(new OBOStorageException(cls, disjoint, "Found anonymous disjoint class that cannot be represented in OBO"));
            }
        }

        for (OBORelationship relationship : relationshipHandler.getOBORelationships()) {
            // TODO handle datatype restrictions
            handleRelationship(relationship, tvpList);
        }

        tvpList.write(writer);
    }


    private void handleIntersection(OWLClass cls, OWLObjectIntersectionOf intersectionOf, OBOTagValuePairList tvpList) {
        for (OWLClassExpression op : intersectionOf.getOperands()) {
            if (!op.isAnonymous()) {
                tvpList.addPair(OBOVocabulary.INTERSECTION_OF, getID(op.asOWLClass()));
            } else {
                relationshipHandler.setClass(cls);
                op.accept(relationshipHandler);
                Set<OBORelationship> relations = relationshipHandler.getOBORelationships();
                if (!relations.isEmpty()) {
                    OBORelationship rel = relations.iterator().next();
                    tvpList.addPair(OBOVocabulary.INTERSECTION_OF, renderRestriction(rel));
                } else {
                    exceptions.add(new OBOStorageException(cls, op, "Found operand in intersection that cannot be represented"));
                }
            }
        }
        // TODO handle namespace
    }


    private String renderRestriction(OBORelationship rel) {
        StringBuilder sb = new StringBuilder(getID(rel.getProperty()));
        sb.append(" ");
        sb.append(getID(rel.getFiller()));
        return sb.toString();
    }


    private void handleUnion(OWLClass cls, OWLObjectUnionOf union, OBOTagValuePairList tvpList) {
        for (OWLClassExpression op : union.getOperands()) {
            if (!op.isAnonymous()) {
                tvpList.addPair(OBOVocabulary.UNION_OF, getID(op.asOWLClass()));
            } else {
                relationshipHandler.setClass(cls);
                op.accept(relationshipHandler);
                Set<OBORelationship> relations = relationshipHandler.getOBORelationships();
                if (!relations.isEmpty()) {
                    OBORelationship rel = relations.iterator().next();
                    tvpList.addPair(OBOVocabulary.UNION_OF, renderRestriction(rel));
                } else {
                    exceptions.add(new OBOStorageException(cls, op, "Found operand in union that cannot be represented"));
                }
            }
        }
    }


    private void handleRelationship(OBORelationship relationship, OBOTagValuePairList tvpList) {
        StringBuilder sb = new StringBuilder();
        sb.append(renderRestriction(relationship));
        sb.append("\n");

        if (relationship.getCardinality() >= 0) {
            sb.append(OBOVocabulary.CARDINALITY.getName());
            sb.append(":");
            sb.append(Integer.toString(relationship.getCardinality()));
            sb.append("\n");
        }
        if (relationship.getMaxCardinality() >= 0) {
            sb.append(OBOVocabulary.MAX_CARDINALITY.getName());
            sb.append(":");
            sb.append(Integer.toString(relationship.getMaxCardinality()));
            sb.append("\n");
        }
        if (relationship.getMinCardinality() >= 0) {
            sb.append(OBOVocabulary.MIN_CARDINALITY.getName());
            sb.append(":");
            sb.append(Integer.toString(relationship.getMinCardinality()));
            sb.append("\n");
        }
        tvpList.addPair(OBOVocabulary.RELATIONSHIP, sb.toString());
    }


    private <P extends OWLProperty> OBOTagValuePairList handleCommonTypeDefStanza(P property, OWLOntology ontology, Writer writer) {
        write("\n[", writer);
        write(OBOVocabulary.TYPEDEF.getName(), writer);
        write("]\n", writer);

        OBOTagValuePairList tvpList = new OBOTagValuePairList(OBOVocabulary.getTypeDefStanzaTags());
        handleEntityBase(property, ontology, tvpList);

        Set<OWLClassExpression> domains = property.getDomains(ontology);
        for (OWLClassExpression domain : domains) {
            if (!domain.isAnonymous()) {
                tvpList.addPair(OBOVocabulary.DOMAIN, getID(domain.asOWLClass()));
            } else {
                exceptions.add(new OBOStorageException(property, domain, "Anonymous domain that cannot be represented in OBO"));
            }
        }

        final Set<OWLPropertyExpression> sp = property.getSuperProperties(ontology);
        for (OWLPropertyExpression superProp : sp) {
            if (!superProp.isAnonymous()) {
                tvpList.addPair(OBOVocabulary.IS_A, getID((OWLProperty) superProp));
            } else {
                exceptions.add(new OBOStorageException(property, superProp, "Anonymous property in superProperty is not supported in OBO"));
            }
        }

        tvpList.setDefault(OBOVocabulary.IS_METADATA_TAG, "false"); // annotation properties only

        return tvpList;
    }


    private void writeTypeDefStanza(OWLObjectProperty property, OWLOntology ontology, Writer writer) {

        OBOTagValuePairList tvpList = handleCommonTypeDefStanza(property, ontology, writer);

        for (OWLClassExpression range : property.getRanges(ontology)) {
            if (!range.isAnonymous()) {
                tvpList.addPair(OBOVocabulary.RANGE, getID(range.asOWLClass()));
            } else {
                exceptions.add(new OBOStorageException(property, range, "Anonymous range that cannot be represented in OBO"));
            }
        }

        if (property.isAsymmetric(ontology)) {
            tvpList.addPair(OBOVocabulary.IS_ANTI_SYMMETRIC, "true");
        }
        if (property.isReflexive(ontology)) {
            tvpList.addPair(OBOVocabulary.IS_REFLEXIVE, "true");
        }
        if (property.isSymmetric(ontology)) {
            tvpList.addPair(OBOVocabulary.IS_SYMMETRIC, "true");
        }
        if (property.isTransitive(ontology)) {
            tvpList.addPair(OBOVocabulary.IS_TRANSITIVE, "true");
        }

        for (OWLObjectPropertyExpression inv : property.getInverses(ontology)) {
            if (!inv.isAnonymous()) {
                tvpList.addPair(OBOVocabulary.INVERSE, getID(inv.asOWLObjectProperty()));
            } else {
                exceptions.add(new OBOStorageException(property, inv, "Anonymous property in inverse is not supported in OBO"));
            }
        }

        // transitive over
        for (OWLSubPropertyChainOfAxiom ax : ontology.getPropertyChainSubPropertyAxioms()) {
            if (ax.getSuperProperty().equals(property)) {
                final List<OWLObjectPropertyExpression> chain = ax.getPropertyChain();
                if (chain.size() == 2 &&
                        chain.get(0).equals(property) &&
                        !chain.get(1).isAnonymous()) {
                    tvpList.addPair(OBOVocabulary.TRANSITIVE_OVER, getID(chain.get(1).asOWLObjectProperty()));
                } else {
                    exceptions.add(new OBOStorageException(property, ax, "Only property chains of form 'p o q -> p' supported"));
                }
            }
        }

        // TODO is RELATIONSHIP really a part of the typedef stanza?

        tvpList.write(writer);
    }


    private void writeTypeDefStanza(OWLDataProperty property, OWLOntology ontology, Writer writer) {

        OBOTagValuePairList tvpList = handleCommonTypeDefStanza(property, ontology, writer);

        for (OWLDataRange range : property.getRanges(ontology)) {
            if (range.isDatatype()) {
                tvpList.addPair(OBOVocabulary.RANGE, range.asOWLDatatype().getURI().toString());
            } else {
                exceptions.add(new OBOStorageException(property, range, "Complex data range cannot be represented in OBO"));
            }
        }

        // TODO is RELATIONSHIP really a part of the typedef stanza?

        tvpList.write(writer);
    }

    private void writeInstanceStanza(OWLNamedIndividual individual, OWLOntology ontology, Writer writer) {
        write("\n[", writer);
        write(OBOVocabulary.INSTANCE.getName(), writer);
        write("]\n", writer);

        OBOTagValuePairList tvpList = new OBOTagValuePairList(OBOVocabulary.getInstanceStanzaTags());
        if (individual.isAnonymous()) {
            tvpList.setDefault(OBOVocabulary.IS_ANONYMOUS, "true");
        }
        handleEntityBase(individual, ontology, tvpList);

        for (OWLClassExpression type : individual.getTypes(ontology)) {
            if (!type.isAnonymous()) {
                tvpList.addPair(OBOVocabulary.INSTANCE_OF, getID(type.asOWLClass()));
            } else {
                exceptions.add(new OBOStorageException(individual, type, "Complex types cannot be represented in OBO"));
            }
        }

        Map<OWLObjectPropertyExpression, Set<OWLIndividual>> objPropAssertions = individual.getObjectPropertyValues(ontology);
        for (OWLObjectPropertyExpression p : objPropAssertions.keySet()) {
            if (!p.isAnonymous()) {
                for (OWLIndividual ind : objPropAssertions.get(p)) {
                    if (!ind.isAnonymous()) {
                        final String rel = renderRestriction(new OBORelationship(p.asOWLObjectProperty(), ind.asNamedIndividual()));
                        tvpList.addPair(OBOVocabulary.PROPERTY_VALUE, rel);
                    }
                }
            } else {
                exceptions.add(new OBOStorageException(individual, p, "Anonymous property in assertion is not supported in OBO"));
            }
        }

        Map<OWLDataPropertyExpression, Set<OWLLiteral>> dataPropAssertions = individual.getDataPropertyValues(ontology);
        for (OWLDataPropertyExpression p : dataPropAssertions.keySet()) {
            if (!p.isAnonymous()) {
                for (OWLLiteral literal : dataPropAssertions.get(p)) {
                    final String rel = renderPropertyAssertion(p.asOWLDataProperty(), literal);
                    tvpList.addPair(OBOVocabulary.PROPERTY_VALUE, rel);
                }
            } else {
                // no anonymous data properties so this should never occur
                exceptions.add(new OBOStorageException(individual, p, "Anonymous property in assertion is not supported in OBO"));
            }
        }

        tvpList.write(writer);
    }


    private void handleEntityBase(OWLEntity entity, OWLOntology ontology, OBOTagValuePairList tvpList) {
        tvpList.addPair(OBOVocabulary.ID, getID(entity));
        Set<OWLAnnotation> potentialNames = new HashSet<OWLAnnotation>();
        for (OWLAnnotation annotation : entity.getAnnotations(ontology)) {
            if (annotation.getProperty().getIRI().equals(OWLRDFVocabulary.RDFS_LABEL.getIRI())) {
                potentialNames.add(annotation);
            } else if (annotation.getProperty().isComment()) {
                tvpList.addPair(OBOVocabulary.COMMENT, ((OWLLiteral) annotation.getValue()).getLiteral());
            } else {
                tvpList.visit(annotation);
            }
        }

        if (tvpList.getValues(OBOVocabulary.NAME).isEmpty()) { // one name required!!
            if (!potentialNames.isEmpty()) {
                OWLAnnotation firstLabel = potentialNames.iterator().next();
                tvpList.addPair(OBOVocabulary.NAME, ((OWLLiteral) firstLabel.getValue()).getLiteral());
                potentialNames.remove(firstLabel);
            } else {
                tvpList.addPair(OBOVocabulary.NAME, getID(entity));
            }
        }

        // other labels just get rendered as label
        for (OWLAnnotation anno : potentialNames) {
            tvpList.visit(anno);
        }

        final String uri = entity.getURI().toString();
        if (!uri.startsWith(defaultNamespace)) {
            String[] pair = new String[2];
            nsUtil.split(uri, pair);
            final URI base = URI.create(pair[0]);
            String prefix = nsUtil.getPrefix(base.toString());
            tvpList.setDefault(OBOVocabulary.NAMESPACE, prefix);
        }
    }


    private String renderPropertyAssertion(OWLDataProperty property, OWLLiteral literal) {
        StringBuilder sb = new StringBuilder(getID(property));
        sb.append(" \"");
        sb.append(literal.getLiteral());
        sb.append("\" ");
        if (literal.isTyped()) {
            sb.append(literal.asOWLStringLiteral().getDatatype().getURI());
        }
        return sb.toString();
    }


    private String getID(OWLEntity entity) {
        return sfp.getShortForm(entity);
    }


    private void write(String s, Writer writer) {
        try {
            writer.write(s);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }


    private DateFormat getTimestampFormatter() {
        SimpleDateFormat sdf = new SimpleDateFormat();
        sdf.applyPattern("dd:MM:yyyy HH:mm");
        return sdf;
    }
}