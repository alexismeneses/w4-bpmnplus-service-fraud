w4-bpmnplus-service-fraud
=========================

This module allow to send documents to [ITESOFT Fraud Detection SaaS](http://itesoft-fraud-front.azurewebsites.net).

To be able to successfully use this service, you will need an account, a valid license and a valid subscription key.

Build
-----

Download the sources 

    git clone <url>

Compile and package with Maven

    mvn clean package

The zip and tar.gz files required for installation described below will be generated in `target` subdirectory

Installation
------------

### Extraction

Extract the package, either zip or tar.gz, at the root of a W4 BPMN+ Engine installation. It will create the necessary entries into `services` subdirectory of W4 BPMN+ Engine.

### Configuration

Locate and create or modify the file `W4BPMPLUS_HOME/services/bpmnplus-service-fraud-1.0/conf/fraud.properties` 
to configure the options of the service

Add your subscription key given by ITESOFT Support Team in `subscriptionKey=myPrivateKey`

You can configure which are the detail entities (ECI entities really sent to the fraud SaaS) according to the the master entity (ECI entity received as an input data entry by the service) with keys like `master.<eci:type>.details=<eci:type>,<eci:type>`

Then you can configure for each detail entity, the fraud detection algorithm you wish to use with keys like `detail.<eci:type>.algorithm`. The algorithm may be one of `IDENTITYCARD`, `PASSPORT`, ... Please refer to the documentation or the catalog to discover all possible algorithms. 

To finish, you can configure mappings between detail entity indexes and fraud results with keys like `mapping.detail.<eci:type>.<eci:propertyDefinitionName>=<fraud-result>. 

Example of a complete configuration
 
    detail.doc:identitycard:fr.algorithm=IDENTITYCARD
    detail.doc:passport:fr.algorithm=PASSPORT
    detail.doc:residencepermit:fr.algorithm=VISA
    detail.doc:bankcoordinates:fr.algorithm=RIB

    master.env:default.details=doc:default
    master.doc:default.details=doc:default

    mapping.detail.doc:default.fraud:validity=result.valid
    mapping.detail.doc:default.fraud:statusText=result.statusText
    mapping.detail.doc:identity.firstName=result.details.IDENTITY_DETAIL.split(",")[0]
    mapping.detail.doc:identity.lastName=result.details.IDENTITY_DETAIL.split(",")[1]

Usage
-----

In BPMN+ Composer, use this service in Service Tasks. They must have one input data entry, which must be an ECI item (the master entity).
