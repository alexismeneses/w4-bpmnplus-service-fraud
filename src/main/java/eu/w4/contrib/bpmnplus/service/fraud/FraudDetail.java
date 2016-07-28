package eu.w4.contrib.bpmnplus.service.fraud;

public class FraudDetail
{
  private String _name;
  private String _description;
  private boolean _status;
  private String _statusText;

  public String getName()
  {
    return _name;
  }

  public void setName(String name)
  {
    _name = name;
  }

  public String getDescription()
  {
    return _description;
  }

  public void setDescription(String description)
  {
    _description = description;
  }

  public boolean isStatus()
  {
    return _status;
  }

  public void setStatus(boolean status)
  {
    _status = status;
  }

  public String getStatusText()
  {
    return _statusText;
  }

  public void setStatusText(String statusText)
  {
    _statusText = statusText;
  }
}
