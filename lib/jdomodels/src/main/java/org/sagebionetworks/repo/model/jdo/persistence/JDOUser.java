package org.sagebionetworks.repo.model.jdo.persistence;

import java.util.Date;

import javax.jdo.annotations.IdGeneratorStrategy;
import javax.jdo.annotations.PersistenceCapable;
import javax.jdo.annotations.Persistent;
import javax.jdo.annotations.PrimaryKey;
import javax.jdo.annotations.Unique;

import org.sagebionetworks.repo.model.jdo.JDOBase;


/**
 * This is the representation of a user in the datastore.
 * Note:  Most of the attributes (name, email, password) are stored
 * external to the datastore.  This class merely supports authorization.
 * 
 * @author bhoff
 *
 */
@PersistenceCapable(detachable = "false")
public class JDOUser implements JDOBase {

	@PrimaryKey
	@Persistent(valueStrategy = IdGeneratorStrategy.IDENTITY)
	private Long id;
	
	@Persistent
	private Date creationDate;

	// Note:  This is the externally generated ID (perhaps chosen by the user), as distinct from 
	// 'id' which is the key generated by the data store
	@Persistent
	@Unique
	private String userId;

	@Persistent
	private String iamAccessId;
	
	@Persistent 
	private String iamSecretKey;
	
	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Date getCreationDate() {
		return creationDate;
	}

	public void setCreationDate(Date creationDate) {
		this.creationDate = creationDate;
	}

	/**
	 * @return the externally generated ID (perhaps chosen by the user)
	 */
	public String getUserId() {
		return userId;
	}

	/**
	 * Set the externally generated ID (perhaps chosen by the user)
	 * 
	 * @param userId
	 */
	public void setUserId(String userId) {
		this.userId = userId;
	}
	
	public String toString() {return getUserId();}

	/**
	 * @return the iamAccessId
	 */
	public String getIamAccessId() {
		return iamAccessId;
	}

	/**
	 * @param iamAccessId the iamAccessId to set
	 */
	public void setIamAccessId(String iamAccessId) {
		this.iamAccessId = iamAccessId;
	}

	/**
	 * @return the iamSecretKey
	 */
	public String getIamSecretKey() {
		return iamSecretKey;
	}

	/**
	 * @param iamSecretKey the iamSecretKey to set
	 */
	public void setIamSecretKey(String iamSecretKey) {
		this.iamSecretKey = iamSecretKey;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#hashCode()
	 */
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((id == null) ? 0 : id.hashCode());
		return result;
	}

	/* (non-Javadoc)
	 * @see java.lang.Object#equals(java.lang.Object)
	 */
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (!(obj instanceof JDOUser))
			return false;
		JDOUser other = (JDOUser) obj;
		if (id == null) {
			if (other.id != null)
				return false;
		} else if (!id.equals(other.id))
			return false;
		return true;
	}

	

}
