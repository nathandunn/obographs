package org.geneontology.obographs.owlapi;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.geneontology.obographs.io.PrefixHelper;
import org.geneontology.obographs.model.Edge;
import org.geneontology.obographs.model.Graph;
import org.geneontology.obographs.model.GraphDocument;
import org.geneontology.obographs.model.Meta;
import org.geneontology.obographs.model.Node;
import org.geneontology.obographs.model.Node.Builder;
import org.geneontology.obographs.model.Node.RDFTYPES;
import org.geneontology.obographs.model.axiom.ExistentialRestrictionExpression;
import org.geneontology.obographs.model.meta.DefinitionPropertyValue;
import org.geneontology.obographs.model.meta.XrefPropertyValue;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnnotationSubject;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLLogicalAxiom;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLPropertyExpression;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.jsonldjava.core.Context;


public class FromOwl {

    public static final String SUBCLASS_OF = "is_a";

    private PrefixHelper prefixHelper;
    private Context context;

    public FromOwl() {
        prefixHelper = new PrefixHelper();
        context = prefixHelper.getContext();
    }

    /**
     * @param baseOntology
     * @return GraphDocument where each graph is an ontology in the ontology closure
     */
    public GraphDocument generateGraphDocument(OWLOntology baseOntology) {
        List<Graph> graphs = new ArrayList<>();
        for (OWLOntology ont : baseOntology.getImportsClosure()) {
            graphs.add( generateGraph(ont));
        }
        return new GraphDocument.Builder().graphs(graphs).build();
    }

    /**
     * @param ontology
     * @return Graph corresponding to ontology
     */
    public Graph generateGraph(OWLOntology ontology) {

        List<Edge> edges = new ArrayList<>();
        List<Node> nodes = new ArrayList<>();
        Set<String> nodeIds = new HashSet<>();
        Map<String,RDFTYPES> nodeTypeMap = new HashMap<>();
        Map<String,String> nodeLabelMap = new HashMap<>();
        Map<String,DefinitionPropertyValue> nodeDefinitionMap = new HashMap<>();
        Map<String,Meta.Builder> nodeMetaBuilderMap = new HashMap<>();
        Set<OWLAxiom> untranslatedAxioms = new HashSet<>();

        for (OWLAxiom ax : ontology.getAxioms()) {

            Meta meta = getAnnotations(ax);
            
            if (ax instanceof OWLDeclarationAxiom) {
                OWLDeclarationAxiom dax = ((OWLDeclarationAxiom)ax);
                OWLEntity e = dax.getEntity();
                if (e instanceof OWLClass) {
                    setNodeType(getClassId((OWLClass) e), RDFTYPES.CLASS, nodeTypeMap);
                }
            }
            else if (ax instanceof OWLLogicalAxiom) {
                if (ax instanceof OWLSubClassOfAxiom) {
                    OWLSubClassOfAxiom sca = (OWLSubClassOfAxiom)ax;
                    OWLClassExpression subc = sca.getSubClass();
                    OWLClassExpression supc = sca.getSuperClass();
                    if (subc.isAnonymous()) {
                        untranslatedAxioms.add(sca);
                    }
                    else {
                        String subj = getClassId((OWLClass) subc);
                        setNodeType(subj, RDFTYPES.CLASS, nodeTypeMap);
                        
                        if (supc.isAnonymous()) {
                            ExistentialRestrictionExpression r = getRestriction(supc);
                            edges.add(getEdge(subj, r.getPropertyId(), r.getFillerId()));
                        }
                        else {
                            edges.add(getEdge(subj, SUBCLASS_OF, getClassId((OWLClass) supc)));

                        }
                    }
                }
            }
            else {
                if (ax instanceof OWLAnnotationAssertionAxiom) {
                    OWLAnnotationAssertionAxiom aaa = (OWLAnnotationAssertionAxiom)ax;
                    OWLAnnotationProperty p = aaa.getProperty();
                    OWLAnnotationSubject s = aaa.getSubject();
                    if (s instanceof IRI) {


                        String subj = getNodeId((IRI)s);

                        OWLAnnotationValue v = aaa.getValue();
                        if (p.isLabel()) {
                            if (v instanceof OWLLiteral) {
                                OWLLiteral lit = (OWLLiteral)v;

                                nodeIds.add(subj);
                                nodeLabelMap.put(subj, lit.getLiteral());
                            }
                        }
                        else if (isDefinitionProperty(p.getIRI())) {
                            if (v instanceof OWLLiteral) {
                                OWLLiteral lit = (OWLLiteral)v;
                                DefinitionPropertyValue def = 
                                        new DefinitionPropertyValue.Builder().
                                        val(lit.getLiteral()).
                                        xrefs(meta.getXrefsValues()).
                                        build();

                                Meta.Builder nb = put(nodeMetaBuilderMap, subj);
                                nb.definition(def);
                                nodeIds.add(subj);
                                nodeDefinitionMap.put(subj, def);
                            }

                        }

                    }
                    else {
                        // subject is anonymous
                        untranslatedAxioms.add(aaa);
                    }

                }
            }
        }

        for (String n : nodeIds) {
            Builder nb = new Node.Builder().
                    id(n).
                    label(nodeLabelMap.get(n));
            if (nodeMetaBuilderMap.containsKey(n)) {
                Meta meta = nodeMetaBuilderMap.get(n).build();
                nb.meta(meta);
            }
            if (nodeTypeMap.containsKey(n)) {
                nb.type(nodeTypeMap.get(n));
            }
            nodes.add(nb.build());
        }
        return new Graph.Builder().nodes(nodes).edges(edges).build();
    }


    private void setNodeType(String id, RDFTYPES t,
            Map<String, RDFTYPES> nodeTypeMap) {
        nodeTypeMap.put(id, t);
    }

    private Meta.Builder put(Map<String, Meta.Builder> nodeMetaBuilderMap, String id) {
        if (!nodeMetaBuilderMap.containsKey(id))
            nodeMetaBuilderMap.put(id, new Meta.Builder());
        return nodeMetaBuilderMap.get(id);
    }

    /**
     * Translate all axiom annotations into a Meta object
     * 
     * @param ax
     * @return
     */
    private Meta getAnnotations(OWLAxiom ax) {
        List<XrefPropertyValue> xrefs = new ArrayList<>();
        for (OWLAnnotation ann : ax.getAnnotations()) {
            OWLAnnotationProperty p = ann.getProperty();
            OWLAnnotationValue v = ann.getValue();
            if (isHasXrefProperty(p.getIRI())) {
                String val = v instanceof IRI ? ((IRI)v).toString() : ((OWLLiteral)v).getLiteral();
                xrefs.add(new XrefPropertyValue.Builder().val(val).build());
            }
            else {
                // TODO
            }
        }
        return new Meta.Builder().xrefs(xrefs).build();
    }



    private Edge getEdge(String subj, String pred, String obj) {
        return new Edge.Builder().subj(subj).pred(pred).obj(obj).build();
    }

    private ExistentialRestrictionExpression getRestriction(
            OWLClassExpression x) {
        if (x instanceof OWLObjectSomeValuesFrom) {
            OWLObjectSomeValuesFrom r = (OWLObjectSomeValuesFrom)x;
            OWLPropertyExpression p = r.getProperty();
            OWLClassExpression f = r.getFiller();
            if (p instanceof OWLObjectProperty && !f.isAnonymous()) {

                return new ExistentialRestrictionExpression.Builder()
                .propertyId(getPropertyId((OWLObjectProperty) p))
                .fillerId(getClassId((OWLClass) f))
                .build();
            }
        }
        return null;
    }

    //    private String shortenIRI(IRI iri) {
    //        prefixHelper
    //    }

    private String getPropertyId(OWLObjectProperty p) {
        return p.getIRI().toString();
    }
    private String getClassId(OWLClass c) {
        return c.getIRI().toString();
    }

    private String getNodeId(IRI s) {
        return s.toString();
    }

    public boolean isDefinitionProperty(IRI iri) {
        return iri.toString().equals("http://purl.obolibrary.org/obo/IAO_0000115");
    }

    public boolean isHasXrefProperty(IRI iri) {
        return iri.toString().equals("http://www.geneontology.org/formats/oboInOwl#hasDbXref");
    }
}
