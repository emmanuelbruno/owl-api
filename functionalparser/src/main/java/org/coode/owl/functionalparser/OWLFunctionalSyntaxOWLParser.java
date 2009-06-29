package org.coode.owl.functionalparser;

import org.semanticweb.owlapi.io.AbstractOWLParser;
import org.semanticweb.owlapi.io.OWLOntologyInputSource;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyFormat;
/*
 * Copyright (C) 2006, University of Manchester
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
 * Author: Matthew Horridge<br>
 * The University Of Manchester<br>
 * Bio-Health Informatics Group<br>
 * Date: 14-Nov-2006<br><br>
 */
public class OWLFunctionalSyntaxOWLParser extends AbstractOWLParser {

    public OWLOntologyFormat parse(OWLOntologyInputSource inputSource, OWLOntology ontology) throws OWLOntologyCreationException {
        try {
            OWLFunctionalSyntaxParser parser;
            if(inputSource.isReaderAvailable()) {
                parser = new OWLFunctionalSyntaxParser(inputSource.getReader());
            }
            else if(inputSource.isInputStreamAvailable()) {
                parser = new OWLFunctionalSyntaxParser(inputSource.getInputStream());
            }
            else {
                parser = new OWLFunctionalSyntaxParser(getInputStream(inputSource.getPhysicalURI()));
            }
            parser.setUp(getOWLOntologyManager(), ontology);
            return parser.parse();
        }
        catch (ParseException e) {
            throw new OWLFunctionalSyntaxParserException(e);
        }
    }
}
