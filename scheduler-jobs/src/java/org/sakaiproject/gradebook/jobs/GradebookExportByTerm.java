package org.sakaiproject.gradebook.jobs;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import lombok.Setter;
import lombok.extern.apachecommons.CommonsLog;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.authz.api.AuthzGroupService;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.coursemanagement.api.AcademicSession;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.event.api.EventTrackingService;
import org.sakaiproject.event.api.UsageSessionService;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.gradebook.model.CSVHelper;
import org.sakaiproject.gradebook.model.StudentGrades;
import org.sakaiproject.service.gradebook.shared.Assignment;
import org.sakaiproject.service.gradebook.shared.CategoryDefinition;
import org.sakaiproject.service.gradebook.shared.CommentDefinition;
import org.sakaiproject.service.gradebook.shared.GradebookNotFoundException;
import org.sakaiproject.service.gradebook.shared.GradebookService;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.site.api.SiteService.SelectionType;
import org.sakaiproject.site.api.SiteService.SortType;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.tool.gradebook.Gradebook;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserDirectoryService;

import com.opencsv.CSVWriter;


/**
 * Job to export gradebook information to CSV for all students in all sites (optionally filtered by term)
 * 
 * @author Steve Swinsburg (steve.swinsburg@gmail.com)
 *
 */
@CommonsLog
public class GradebookExportByTerm implements Job {

	private final String JOB_NAME = "GradebookExportByTerm";
	private final long COURSE_GRADE_ASSIGNMENT_ID = -1; // because no gradeable object in Sakai should have this value
	private final long TOTAL_POINTS_EARNED = -2; // see above
	private final long TOTAL_POINTS_POSSIBLE = -3; // see above

	
	// do all of the work
	// this has been combined into one method. It's a lot of code but it reduces additional lookups and duplication of code, refactor if time allows
	public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
		
		log.info(JOB_NAME + " started.");

		//get admin session
		establishSession(JOB_NAME);

		//get all sites that match the criteria
		String[] termEids = serverConfigurationService.getStrings("gradebook.export.term");
		if (termEids == null || termEids.length < 1) {
			termEids = getCurrentTerms();
		}

		// Loop through all terms provided in sakai.properties
		for (String termEid : termEids) {

			List<Site> sites = getSites(termEid);

			log.info("Sites to process for term " + termEid + ": " + sites.size());

		for(Site s:sites) {

			String siteId = s.getId();

			//get the grades for each site
			List<StudentGrades> grades = new ArrayList<StudentGrades>();
			log.info("Processing site: " + siteId + " - " + s.getTitle());

			//get users in site, skip if none
			List<User> users = getValidUsersInSite(siteId);
			Collections.sort(users, new LastNameComparator());
			if(users == null || users.isEmpty()) {
				log.info("No users in site: " + siteId + ", skipping.");
				continue;
			}

			//get gradebook for this site, skip if none
			Gradebook gradebook = null;
			List<Assignment> assignments = new ArrayList<Assignment>();
			List<CategoryDefinition> categoryDefinitions = new ArrayList<CategoryDefinition>();

			try {
				gradebook = (Gradebook)gradebookService.getGradebook(siteId);

				//get list of assignments in gradebook, skip if none
				assignments = gradebookService.getAssignments(gradebook.getUid());
				if(assignments == null || assignments.isEmpty()) {
					log.info("No assignments for site: " + siteId + ", skipping.");
					continue;
				}
				log.debug("Assignments size: " + assignments.size());

				//get course grades. This uses entered grades preferentially
				Map<String, String> courseGrades = gradebookService.getImportCourseGrade(gradebook.getUid()); 

				//get any categories
				categoryDefinitions = gradebookService.getCategoryDefinitions(siteId);

				//for each user, get the assignment results for each assignment, with TPE and course grade at the end
				for(User u: users) {

					StudentGrades g = new StudentGrades(u.getId(), u.getEid());

					log.debug("Member: " + u.getId() + " - " + u.getEid());

					//add in the displayname (lastname, firstname)
					g.setDisplayName(u.getSortName());

					//if a user has no grade for the assignment ensure they are not missed
					for(Assignment a: assignments) {

						log.debug("Assignment: " + a.getId() + ": " + a.getName());

						String points = gradebookService.getAssignmentScoreString(gradebook.getUid(), a.getId(), u.getId());
						g.addGrade(a.getId(), points);

						log.debug("Points: " + points);
					}

					//determine a grade for any categories
					for(CategoryDefinition cd: categoryDefinitions) {
						g.addGrade(cd.getId(), getDisplayGradeForCategory(gradebook.getUid(), u.getId(), cd));
					}

					//add total points earned
					g.addGrade(TOTAL_POINTS_EARNED, this.getTotalPointsEarned(gradebook.getUid(), u.getId(), assignments));

					//add total points possible
					g.addGrade(TOTAL_POINTS_POSSIBLE, this.getTotalPointsPossible(gradebook.getUid(), u.getId(), assignments));				

					//add the course grade. Note the map has eids.
					g.addGrade(COURSE_GRADE_ASSIGNMENT_ID, courseGrades.get(u.getEid()));

					log.debug("Course Grade: " + courseGrades.get(u.getEid()));

					grades.add(g);
				}
			} catch (GradebookNotFoundException gbe) {
				log.info("No gradebook for site: " + siteId + ", skipping.");
				continue;
			} catch (Exception e) {
				log.error("Problem while processing gbExport for site: " + siteId, e);
				continue;
			}
		
			//now write the grades
			if(!grades.isEmpty()) {			
				
				String file;
				if (StringUtils.endsWith(getOutputPath(), File.separator)) {
					file = getOutputPath() + siteId + ".csv";
				} else {
					file = getOutputPath() + File.separator + siteId + ".csv";
				}
						
				//delete existing file so we know the data is current
				if(deleteFile(file)) {
					log.debug("New file: " + file);
				}
				
				CSVWriter writer;
				try {
					writer = new CSVWriter(new FileWriter(file), ',');
			   
					CSVHelper csv = new CSVHelper();
					
					//set the CSV header from the assignment titles and add additional fields
					List<String> header = new ArrayList<String>();
					header.add("Student ID");
					header.add("Student Name");
					
					//add assignment name and then the points possible for the assignment
					//then another column for the comments
					for(Assignment a: assignments) {
						header.add(a.getName() + " [" + a.getPoints() + "]");
						header.add("Comments");
					}
					
					//add the category header (category name  + weighting percentage)
					for(CategoryDefinition cd: categoryDefinitions) {
						Double weight = cd.getWeight();
						if(weight != null && weight != 0) {
							header.add(cd.getName() + " [" + fractionToPercentage(weight, 3) + "]"); //display as percentage. it's stored as a fraction  ie 0.1 for 10%
						} else {
							header.add(cd.getName());
						}
					}
					
					//add these too
					header.add("Total Points Earned [Points Possible]");
					header.add("Course Grade");
					
					// Make sure all row sizes are consistent
					int headerSize = header.size();
					
					csv.setHeader(header.toArray(new String[headerSize]));
					
					//create a formatted list of data using the grade records info and user info, using the order of the assignment list
					//this puts it in the order we need for the CSV
					for(StudentGrades sg: grades) {
						
						List<String> row = new ArrayList<String>(headerSize);
						
						//add name details
						row.add(sg.getUserEid());
						row.add(sg.getDisplayName());		
						
						//add grades
						Map<Long,String> g = sg.getGrades();
						for(Assignment a: assignments) {
							row.add(g.get(a.getId()));
							
							//get comment for each assignment
							CommentDefinition commentDefinition = gradebookService.getAssignmentScoreComment(gradebook.getUid(), a.getId(), sg.getUserId());
							String comment = null;
							if(commentDefinition != null) {
								comment = commentDefinition.getCommentText();
							}
							row.add(comment);
						}
						
						//add category grades
						for(CategoryDefinition cd: categoryDefinitions) {
							row.add(g.get(cd.getId()));
						}
						
						//add total points earned and possible
						row.add(g.get(TOTAL_POINTS_EARNED) + " [" + g.get(TOTAL_POINTS_POSSIBLE) + "]");
						
						//add course grade
						row.add(g.get(COURSE_GRADE_ASSIGNMENT_ID));
						
						// Make sure row is same size as header
						if (row.size() != headerSize) {
							log.error("Row not same size as header: " + row.size () + " vs header size of " + headerSize);
						}
						
						log.debug("Row: " + row);

						csv.addRow(row.toArray(new String[row.size()]));
					}
					
					//add a row to show the grade mapping (sorted via the value) (2 columns)
					Map<String,Double> baseMap = gradebook.getSelectedGradeMapping().getGradeMap();
			        ValueComparator gradeMappingsComparator = new ValueComparator(baseMap);
			        TreeMap<String,Double> sortedGradeMappings = new TreeMap<String,Double>(gradeMappingsComparator);
			        sortedGradeMappings.putAll(baseMap);
			        
					List<String> mappings = new ArrayList<String>();
					for(String key: sortedGradeMappings.keySet()) {
						mappings.add(key + "=" + baseMap.get(key));
					}
					
					// Informational rows. Need to fill out the rows for CSV consistency
					List<String> spacerRow = new ArrayList<String>();
					List<String> siteIdRow = new ArrayList<String>();
					List<String> siteTitleRow = new ArrayList<String>();
					List<String> mappingRow = new ArrayList<String>();
					
					siteIdRow.add("Site ID");
					siteIdRow.add(s.getId());
					siteTitleRow.add("Site Title");
					siteTitleRow.add(s.getTitle());
					mappingRow.add("Mappings");
					mappingRow.add(StringUtils.join(mappings, ','));
					
					for (int i = 0; i < headerSize; i++) {
						if (spacerRow.size() < headerSize) spacerRow.add("");
						if (siteIdRow.size() < headerSize) siteIdRow.add("");
						if (siteTitleRow.size() < headerSize) siteTitleRow.add("");
						if (mappingRow.size() < headerSize) mappingRow.add("");
					}

					csv.addRow(spacerRow.toArray(new String[spacerRow.size()]));
					csv.addRow(siteIdRow.toArray(new String[siteIdRow.size()]));
					csv.addRow(siteTitleRow.toArray(new String[siteTitleRow.size()]));
					csv.addRow(mappingRow.toArray(new String[mappingRow.size()]));
					
					//write it all out
					writer.writeNext(csv.getHeader());
					writer.writeAll(csv.getRows());
					writer.close();
					
					log.info("Successfully wrote CSV to: " + file);
			           
				} catch (IOException e) {
					log.equals("Error writing CSV: " + e.getClass() + " : " + e.getMessage());
				}
			}
		}
		}
		
		log.info(JOB_NAME + " ended.");
	}
	
	
	/**
	 * Start a session for the admin user and the given jobName
	 */
	private void establishSession(String jobName) {
		
		//set the user information into the current session
	    Session sakaiSession = sessionManager.getCurrentSession();
	    sakaiSession.setUserId("admin");
	    sakaiSession.setUserEid("admin");

	    //establish the user's session
	    usageSessionService.startSession("admin", "127.0.0.1", "gradebook-export");
	
	    //update the user's externally provided realm definitions
	    authzGroupService.refreshUser("admin");

	    //post the login event
	    eventTrackingService.post(eventTrackingService.newEvent(UsageSessionService.EVENT_LOGIN, null, true));
	}
	
	
	/**
	 * Get configurable output path. Defaults to /tmp
	 * @return
	 */
	private String getOutputPath() {
		return serverConfigurationService.getString("gradebook.export.path", FileUtils.getTempDirectoryPath());
	}
	
	/**
	 * Get all sites that match the criteria, filter out special sites and my workspace sites
	 * @return
	 */
	private List<Site> getSites(String termEid) {

		//setup property criteria
		//this could be extended to dynamically fill the map with properties and values from sakai.props
		Map<String, String> propertyCriteria = new HashMap<String,String>();
		propertyCriteria.put("term_eid", termEid);

		List<Site> sites = new ArrayList<Site>();
			
		List<Site> allSites = siteService.getSites(SelectionType.ANY, null, null, propertyCriteria, SortType.TITLE_ASC, null);		
		
		for(Site s: allSites) {
			//filter my workspace
			if(siteService.isUserSite(s.getId())){
				continue;
			}
			
			//filter special sites
			if(siteService.isSpecialSite(s.getId())){
				continue;
			}
			
			log.debug("Site: " + s.getId());
			
			//otherwise add it
			sites.add(s);
		}
		
		return sites;
	}
	
	
	/**
	 * Get the users of a site that have the relevant permission
	 * @param siteId
	 * @return list or null if site is bad
	 */
	private List<User> getValidUsersInSite(String siteId) {
		
		try {
			
			Set<String> userIds = siteService.getSite(siteId).getUsersIsAllowed("gradebook.viewOwnGrades");			
			return userDirectoryService.getUsers(userIds);

		} catch (IdUnusedException e) {
			return null;
		}
		
	}
	
	/**
	 * Helper to delete a file. Will only delete files, not directories.
	 * @param filePath	path to file to delete.
	 * @return
	 */
	private boolean deleteFile(String filePath) {
		try {
			File f = new File(filePath);
			
			//if doesn't exist, return true since we don't need to delete it
			if(!f.exists()) {
				return true;
			}
			
			//check it is a file and delete it
			if(f.isFile()) {
				return f.delete();
			}
			return false;
		} catch (Exception e) {
			return false;
		}
		
	}
	
	/**
	 * Get the most recent active term
	 * @return
	 */
	private String[] getCurrentTerms() {
		Set<String> termSet = new HashSet<>();
		
		List<AcademicSession> sessions = courseManagementService.getCurrentAcademicSessions();
		
		log.debug("terms: " + sessions.size());

		if(sessions.isEmpty()) {
			return null;
		}
				
		for(AcademicSession as: sessions) {
			termSet.add(as.getEid());
			log.debug("term: " + as.getEid());
		}
		
		return termSet.toArray(new String[termSet.size()]);

	}
	
	/**
	 * Helper to get the total number of points possible for all assignments
	 * Takes into account participation, ie student must have attempted the assignment (indicated by having a grade) for it to be included.
	 * Also, EXCLUDES extra credit items.
	 * 
	 * @param gradebookUid
	 * @param userId
	 * @param assignments
	 * @return
	 */
	private String getTotalPointsPossible(String gradebookUid, String userId, List<Assignment> assignments) {
		double totalPointsPossible = 0;
		
		for(Assignment a: assignments) {
			
			if(a.isExtraCredit()){
				continue;
			}			
			
			if(StringUtils.isNotBlank(gradebookService.getAssignmentScoreString(gradebookUid, a.getId(), userId))){
				totalPointsPossible += a.getPoints();
			}
		}
				
		return String.valueOf(totalPointsPossible);
	}
	
	/**
	 * Get the total points earned for a user. Sums their score in all assignments. 
	 * Assumes gradebookService handles the drophighest/lowest/other functionality and returns the correct score for each assignment.
	 * Returns N/A if not available
	 * 
	 * @param gradebookUid
	 * @param userId
	 * @param assignments
	 * @return
	 */
	private String getTotalPointsEarned(String gradebookUid, String userId, List<Assignment> assignments) {
		double totalPointsEarned = 0;
		
		for(Assignment a: assignments) {
			try {
				totalPointsEarned += Double.valueOf(gradebookService.getAssignmentScoreString(gradebookUid, a.getId(), userId));
			} catch (Exception e) {
				//skip to next, nothing entered for this assignment
			}
		}
		
		return String.valueOf(totalPointsEarned);
	}
	
	/**
	 * Format a fraction double into a percentage string
	 * @param d double number. 0-3 decimal places as per gradebook allows
	 * @param precision. number of decimal places
	 * @return 12% string
	 */
	private String fractionToPercentage(double d, int precision) {
		int maxPrecision = 3;
		if(precision > maxPrecision) {
			precision = maxPrecision;
		}
		
		NumberFormat nf = NumberFormat.getPercentInstance();
		nf.setMinimumFractionDigits(0);
		nf.setMaximumFractionDigits(precision);
		return nf.format(d);
	}
	
	/**
	 * Determine a grade for all assignments in the given category categories. Formatted as a percentage. Returns null if not available
	 * Only takes into account those there there was an attempt.
	 * 
	 * @param gradebookUid
	 * @param userId
	 * @param cd
	 * @return
	 */
	private String getDisplayGradeForCategory(String gradebookUid, String userId, CategoryDefinition cd) {
		
		List<Assignment> assignmentsInCategory = cd.getAssignmentList();
		double userPoints = 0;
		double totalPoints = 0;

		for(Assignment a: assignmentsInCategory) {
			try {
				String scoreString = gradebookService.getAssignmentScoreString(gradebookUid, a.getId(), userId);
				if(StringUtils.isNotBlank(scoreString)) {
					userPoints += Double.valueOf(scoreString); //only add if student attempted
					totalPoints += a.getPoints(); //only add if student attempted
				}
			} catch (Exception e) {
				//skip to next
			}
		}
	
		//cater for totalPoints=0, where student hasn't attempted anything in category
		double fraction = 0;
		if(totalPoints != 0) {
			fraction = userPoints / totalPoints;
		}
		String percentage = fractionToPercentage(fraction, 1);
		
		return percentage;
	}
	
	
	@Setter
	private SessionManager sessionManager;
	
	@Setter
	private UsageSessionService usageSessionService;
	
	@Setter
	private AuthzGroupService authzGroupService;
	
	@Setter
	private EventTrackingService eventTrackingService;
	
	@Setter
	private ServerConfigurationService serverConfigurationService;
	
	@Setter
	private SiteService siteService;
	
	@Setter
	private UserDirectoryService userDirectoryService;
	
	@Setter
	private GradebookService gradebookService;
	
	@Setter
	private CourseManagementService courseManagementService;
	
	@Setter
	private SecurityService securityService;
	
}

/**
 * Comparator class for sorting a list of users by last name
 */
class LastNameComparator implements Comparator<User> {
	
    @Override
    public int compare(User u1, User u2) {
    	return u1.getLastName().compareTo(u2.getLastName());
	}
   
}

/**
 * Comparator class for sorting a grade map by its value
 */
class ValueComparator implements Comparator<String> {

    Map<String, Double> base;
    public ValueComparator(Map<String, Double> base) {
        this.base = base;
    }

    public int compare(String a, String b) {
        if (base.get(a) >= base.get(b)) {
            return -1;
        } else {
            return 1;
        }
    }
}
