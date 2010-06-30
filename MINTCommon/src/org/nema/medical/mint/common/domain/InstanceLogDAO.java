package org.nema.medical.mint.common.domain;

import java.sql.Timestamp;
import java.util.List;

import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Restrictions;
import org.springframework.orm.hibernate3.HibernateTemplate;
import org.springframework.orm.hibernate3.support.HibernateDaoSupport;

public class InstanceLogDAO extends HibernateDaoSupport {

	@SuppressWarnings("rawtypes")
	public InstanceLog logInstance(String associationKey, boolean associationComplete,
			String studyInstanceUID, Timestamp studyDateTime,
			String patientName, String patientID, String accessionNumber,
			String xferSyntax, Timestamp receiveBegin,
			Timestamp receiveComplete, String instanceUID, String classUID,
			String aeTitle, String ipAddress, long size) {

		HibernateTemplate template = getHibernateTemplate();

		// lookup study by studyInstanceUID, create if necessary
		DetachedCriteria studyCriteria = DetachedCriteria.forClass(Study.class)
				.add(Restrictions.eq("studyInstanceUID", studyInstanceUID));
		
		List studies = template.findByCriteria(studyCriteria);
		Study study = (studies.isEmpty()) ? null : (Study)studies.get(0);
		if (study == null) {
			// create the study and initialize
			study = new Study();
			study.setStudyInstanceUID(studyInstanceUID);
		}
		study.setAccessionNumber(accessionNumber);
		study.setPatientID(patientID);
		study.setPatientName(patientName);
		study.setStudyDateTime(studyDateTime);
		// if study existed already and none of the above changed, 
		// hibernate should optimize this out
		template.persist(study);

		DetachedCriteria deviceCriteria = DetachedCriteria.forClass(Device.class)
				.add(Restrictions.eq("aeTitle", aeTitle))
				.add(Restrictions.eq("ipAddress", ipAddress));
		List devices = template.findByCriteria(deviceCriteria);
		Device device = (devices.isEmpty()) ? null : (Device)devices.get(0);
		if (device == null) {
			device = new Device();
			device.setAeTitle(aeTitle);
			device.setIpAddress(ipAddress);
			template.persist(device);
		}
		study.getDevices().add(device);
		
		// lookup association by associationKey, create if necessary
		AssociationLog alog = (AssociationLog) template.get(
				AssociationLog.class, associationKey);
		if (alog == null) {
			alog = new AssociationLog();
			alog.setID(associationKey);
			alog.setAssocBegin(receiveBegin);
			template.persist(alog);
		} else if (alog.getAssocEnd() != null) {
			// todo - make this a real exception
			throw new RuntimeException("trying to update a closed association!");
		} else if (associationComplete) {
			alog.setAssocEnd(receiveComplete);
		}

		// create instanceLog
		InstanceLog ilog = new InstanceLog();
		ilog.setStudy(study);
		ilog.setAssociationLog(alog);
		ilog.setSopInstanceUID(instanceUID);
		ilog.setSopClassUID(classUID);
		ilog.setXferSyntax(xferSyntax);
		ilog.setSize(size);
		template.persist(ilog);
		
		template.flush();
		
		return ilog;
	}
}
